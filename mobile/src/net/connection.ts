// ══════════════════════════════════════════════════════════════
// Connection manager: máquina de estados + auth + reconexión + streams.
//
// Estado:
//   disconnected → connecting → authenticating → connected
//                                    ↓                ↓
//                                  failed        reconnecting → connected
//
// Backoff: 1s → 2s → 4s → 8s → 16s → 30s (tope).
// Ping cada 15s; sin pong en 5s → reconnect.
// ══════════════════════════════════════════════════════════════

import type {
  BaseMessage,
  CommandRequest,
  CommandResponse,
  ErrorInfo,
  StreamMessage,
  EventMessage,
  AuthChallenge,
  AuthResult,
} from "./protocol";
import { MessageKinds } from "./protocol";
import type { AgentCredentials } from "@/storage/secure";
import { base64ToBytes, hexToBytes, signBase64 } from "./crypto";

export type ConnectionState =
  | "disconnected"
  | "connecting"
  | "authenticating"
  | "connected"
  | "reconnecting"
  | "failed";

export interface ConnectionListener {
  onState?(state: ConnectionState, error?: string): void;
  onEvent?(event: EventMessage): void;
}

const BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 30000];
const PING_INTERVAL_MS = 15_000;
const PING_TIMEOUT_MS  = 5_000;

type Pending = {
  resolve: (r: CommandResponse) => void;
  reject:  (e: Error) => void;
  timer:   ReturnType<typeof setTimeout>;
};

type StreamHandler = (data: unknown) => void;

export class ConnectionManager {
  private ws: WebSocket | null = null;
  private state: ConnectionState = "disconnected";
  private backoffIdx = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  private manualClose = false;

  private pending = new Map<string, Pending>();
  private streams = new Map<string, StreamHandler>();
  private listeners = new Set<ConnectionListener>();

  constructor(private creds: AgentCredentials) {}

  // ── Public API ───────────────────────────────────────────
  addListener(l: ConnectionListener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  getState(): ConnectionState { return this.state; }

  connect(): void {
    this.manualClose = false;
    this.openSocket();
  }

  disconnect(): void {
    this.manualClose = true;
    this.clearTimers();
    this.ws?.close();
    this.setState("disconnected");
  }

  /** Envía un request y espera la response. */
  request<T = unknown>(domain: string, action: string, params?: Record<string, unknown>, timeoutMs = 8000): Promise<T> {
    return this.rawSendAwait(
      { kind: MessageKinds.Request, id: makeId(), domain, action, params, ts: Date.now() },
      timeoutMs,
    ).then((r) => {
      if (!r.success) throw asError(r.error);
      return r.data as T;
    });
  }

  /** Suscribe a un stream. Retorna { unsubscribe } que además cancela server-side. */
  subscribe(
    domain: string,
    action: string,
    onData: StreamHandler,
    params?: Record<string, unknown>,
  ): { id: string; unsubscribe: () => void } {
    const id = makeId();
    this.streams.set(id, onData);
    const subMsg: CommandRequest = {
      kind: MessageKinds.Subscribe, id, domain, action, params, ts: Date.now(),
    };
    this.raw(subMsg);
    return {
      id,
      unsubscribe: () => {
        this.streams.delete(id);
        this.raw({ kind: MessageKinds.Unsubscribe, id, ts: Date.now() } as unknown as CommandRequest);
      },
    };
  }

  // ── Socket lifecycle ─────────────────────────────────────
  private openSocket(): void {
    if (this.state === "connecting" || this.state === "connected" || this.state === "authenticating") return;

    this.setState(this.backoffIdx === 0 ? "connecting" : "reconnecting");
    const url = `wss://${this.creds.agentHost}:${this.creds.agentPort}/ws`;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch (e) {
      this.scheduleReconnect((e as Error).message);
      return;
    }
    this.ws = ws;

    ws.onopen = () => {
      // Trigger auth: server issues challenge on any request without a session.
      this.raw({
        kind: MessageKinds.Request, id: "__probe__", domain: "ping", action: "ping", ts: Date.now(),
      });
      this.setState("authenticating");
    };

    ws.onmessage = (ev) => this.onFrame(String(ev.data));
    ws.onerror = (ev) => this.emitError(ev);
    ws.onclose = () => this.onClose();
  }

  private async onFrame(raw: string): Promise<void> {
    let msg: BaseMessage;
    try { msg = JSON.parse(raw) as BaseMessage; } catch { return; }

    switch (msg.kind) {
      case MessageKinds.AuthChallenge: {
        const chal = msg as AuthChallenge;
        const nonce = hexToBytes(chal.nonce);
        const priv  = base64ToBytes(this.creds.privateKeyB64);
        const sig   = await signBase64(priv, nonce);
        this.raw({
          kind: MessageKinds.Auth, deviceId: this.creds.deviceId,
          signature: sig, ts: Date.now(),
        } as unknown as CommandRequest);
        break;
      }
      case MessageKinds.AuthResult: {
        const auth = msg as AuthResult;
        if (auth.success) {
          this.backoffIdx = 0;
          this.setState("connected");
          this.startPing();
        } else {
          this.emitError(auth.error?.message ?? "auth failed");
          this.setState("failed", auth.error?.message);
          this.ws?.close();
        }
        break;
      }
      case MessageKinds.Response: {
        const res = msg as CommandResponse;
        if (res.id === "__probe__") return;
        const p = this.pending.get(res.id);
        if (p) { clearTimeout(p.timer); this.pending.delete(res.id); p.resolve(res); }
        break;
      }
      case MessageKinds.Stream: {
        const s = msg as StreamMessage;
        this.streams.get(s.id)?.(s.data);
        break;
      }
      case MessageKinds.Event: {
        for (const l of this.listeners) l.onEvent?.(msg as EventMessage);
        break;
      }
      case MessageKinds.Pong: {
        if (this.pongTimer) { clearTimeout(this.pongTimer); this.pongTimer = null; }
        break;
      }
    }
  }

  private onClose(): void {
    this.clearPing();
    for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new Error("Connection closed")); }
    this.pending.clear();
    this.streams.clear();
    if (this.manualClose) return;
    this.scheduleReconnect("socket closed");
  }

  private scheduleReconnect(reason: string): void {
    const delay = BACKOFF_MS[Math.min(this.backoffIdx, BACKOFF_MS.length - 1)];
    this.setState("reconnecting", reason);
    this.reconnectTimer && clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.backoffIdx = Math.min(this.backoffIdx + 1, BACKOFF_MS.length - 1);
      this.openSocket();
    }, delay);
  }

  // ── Ping/pong ────────────────────────────────────────────
  private startPing(): void {
    this.clearPing();
    this.pingTimer = setInterval(() => {
      this.raw({ kind: MessageKinds.Ping, ts: Date.now() } as unknown as CommandRequest);
      this.pongTimer && clearTimeout(this.pongTimer);
      this.pongTimer = setTimeout(() => {
        // No pong → cerrar y reconectar
        this.ws?.close();
      }, PING_TIMEOUT_MS);
    }, PING_INTERVAL_MS);
  }

  private clearPing(): void {
    this.pingTimer && clearInterval(this.pingTimer);
    this.pongTimer && clearTimeout(this.pongTimer);
    this.pingTimer = null;
    this.pongTimer = null;
  }

  private clearTimers(): void {
    this.reconnectTimer && clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.clearPing();
  }

  // ── Send helpers ─────────────────────────────────────────
  private raw(msg: CommandRequest | BaseMessage): void {
    if (this.ws?.readyState !== 1 /* OPEN */) return;
    this.ws.send(JSON.stringify(msg));
  }

  private rawSendAwait(req: CommandRequest, timeoutMs: number): Promise<CommandResponse> {
    return new Promise((resolve, reject) => {
      if (this.state !== "connected") {
        reject(new Error(`Not connected (state=${this.state})`));
        return;
      }
      const timer = setTimeout(() => {
        this.pending.delete(req.id);
        reject(new Error(`Timeout waiting for ${req.domain}.${req.action}`));
      }, timeoutMs);
      this.pending.set(req.id, { resolve, reject, timer });
      this.raw(req);
    });
  }

  private setState(state: ConnectionState, error?: string): void {
    this.state = state;
    for (const l of this.listeners) l.onState?.(state, error);
  }

  private emitError(_e: unknown): void { /* hook for future */ }
}

// ── Utils ────────────────────────────────────────────────
let counter = 0;
function makeId(): string {
  return `${Date.now().toString(36)}_${(counter++).toString(36)}`;
}

function asError(e: ErrorInfo | undefined): Error {
  const msg = e ? `[${e.code}] ${e.message}` : "Unknown error";
  return new Error(msg);
}
