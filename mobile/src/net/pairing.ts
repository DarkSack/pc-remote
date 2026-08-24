// ══════════════════════════════════════════════════════════════
// Flujo de pairing:
//   1) Cliente abre WS y envía pair_init
//   2) Recibe pair_init_ack con TTL
//   3) Usuario introduce código 6 dígitos → cliente envía pair_confirm
//   4) Recibe pair_result con { deviceId, certFingerprint }
//   5) Guardar en expo-secure-store
// ══════════════════════════════════════════════════════════════

import { MessageKinds, type BaseMessage, type PairInitAck, type PairResult } from "./protocol";
import { bytesToBase64, generateKeypair } from "./crypto";
import { saveCredentials, type AgentCredentials } from "@/storage/secure";

export interface PairTarget {
  host: string;
  port: number;
  agentName: string; // hostname del PC
}

export type PairPhase = "connecting" | "waiting_code" | "confirming" | "done" | "error";

export class PairingSession {
  private ws: WebSocket | null = null;
  private keys: { privateKey: Uint8Array; publicKey: Uint8Array } | null = null;
  private onPhase: (phase: PairPhase, info?: string) => void;

  constructor(private target: PairTarget, onPhase: (p: PairPhase, info?: string) => void) {
    this.onPhase = onPhase;
  }

  async start(): Promise<void> {
    this.onPhase("connecting");
    this.keys = await generateKeypair();
    this.ws = new WebSocket(`wss://${this.target.host}:${this.target.port}/ws`);

    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ kind: MessageKinds.PairInit, ts: Date.now() }));
    };
    this.ws.onmessage = (ev) => this.handleMessage(String(ev.data));
    this.ws.onerror = () => this.onPhase("error", "socket error");
    this.ws.onclose = () => {
      // If we're not done yet, treat as error.
    };
  }

  /** Called once the user types the 6-digit code. */
  submitCode(code: string, deviceName: string): void {
    if (!this.ws || !this.keys) return;
    this.onPhase("confirming");
    this.ws.send(JSON.stringify({
      kind:       MessageKinds.PairConfirm,
      code,
      deviceName,
      publicKey:  bytesToBase64(this.keys.publicKey),
      ts:         Date.now(),
    }));
  }

  cancel(): void { this.ws?.close(); this.ws = null; }

  private async handleMessage(raw: string): Promise<void> {
    let msg: BaseMessage;
    try { msg = JSON.parse(raw); } catch { return; }

    if (msg.kind === MessageKinds.PairInitAck) {
      const ack = msg as PairInitAck;
      this.onPhase("waiting_code", `Code TTL: ${ack.ttlSec}s`);
      return;
    }

    if (msg.kind === MessageKinds.PairResult) {
      const res = msg as PairResult;
      if (!res.success || !res.deviceId || !res.certFingerprint) {
        this.onPhase("error", res.error?.message ?? "Pair failed");
        this.ws?.close();
        return;
      }
      const creds: AgentCredentials = {
        deviceId:         res.deviceId,
        privateKeyB64:    bytesToBase64(this.keys!.privateKey),
        publicKeyB64:     bytesToBase64(this.keys!.publicKey),
        agentHost:        this.target.host,
        agentPort:        this.target.port,
        agentName:        this.target.agentName,
        certFingerprint:  res.certFingerprint,
      };
      await saveCredentials(creds);
      this.onPhase("done", res.deviceId);
      this.ws?.close();
    }
  }
}
