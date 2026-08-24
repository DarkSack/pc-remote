// ══════════════════════════════════════════════════════════════════
// Tipos del protocolo WebSocket. Espejo de PcRemote.Core.Protocol.
// Ver docs/PROTOCOL.md para el spec completo.
// ══════════════════════════════════════════════════════════════════

export const MessageKinds = {
  Request:       "request",
  Response:      "response",
  Subscribe:     "subscribe",
  Stream:        "stream",
  Unsubscribe:   "unsubscribe",
  Event:         "event",
  Ping:          "ping",
  Pong:          "pong",
  PairInit:      "pair_init",
  PairInitAck:   "pair_init_ack",
  PairConfirm:   "pair_confirm",
  PairResult:    "pair_result",
  AuthChallenge: "auth_challenge",
  Auth:          "auth",
  AuthResult:    "auth_result",
} as const;

export type MessageKind = (typeof MessageKinds)[keyof typeof MessageKinds];

export interface BaseMessage {
  kind: MessageKind;
  ts: number;
}

export interface CommandRequest extends BaseMessage {
  kind: "request" | "subscribe";
  id: string;
  domain: string;
  action: string;
  params?: Record<string, unknown>;
}

export interface CommandResponse extends BaseMessage {
  kind: "response";
  id: string;
  success: boolean;
  data?: unknown;
  error?: ErrorInfo;
}

export interface StreamMessage extends BaseMessage {
  kind: "stream";
  id: string;
  data: unknown;
}

export interface EventMessage extends BaseMessage {
  kind: "event";
  domain: string;
  action: string;
  data: unknown;
}

export interface ErrorInfo {
  code: ErrorCode;
  message: string;
  recoverable?: boolean;
}

export type ErrorCode =
  | "PERMISSION_DENIED"
  | "NOT_AUTHENTICATED"
  | "INVALID_COMMAND"
  | "INVALID_PARAMS"
  | "NOT_FOUND"
  | "TIMEOUT"
  | "INTERNAL_ERROR"
  | "RATE_LIMITED"
  | "PAIRING_FAILED";

// ── Bootstrap ────────────────────────────────────────────────

export interface PairInitAck extends BaseMessage {
  kind: "pair_init_ack";
  ttlSec: number;
}

export interface PairResult extends BaseMessage {
  kind: "pair_result";
  success: boolean;
  deviceId?: string;
  agentPublicKey?: string;
  certFingerprint?: string;
  error?: ErrorInfo;
}

export interface AuthChallenge extends BaseMessage {
  kind: "auth_challenge";
  nonce: string; // hex 32 bytes
}

export interface AuthResult extends BaseMessage {
  kind: "auth_result";
  success: boolean;
  sessionId?: string;
  error?: ErrorInfo;
}

// ── Domain-specific payloads ─────────────────────────────────

export interface SystemInfo {
  hostname: string;
  username: string;
  os: string;
  osBuild: string;
  is64Bit: boolean;
  cpuModel: string;
  cpuCores: number;
  ramTotalMB: number;
  uptimeSec: number;
  timezone: string;
}

export interface SystemStats {
  cpu: number;        // percent 0..100
  ramPct: number;     // percent 0..100
  ramUsedMB: number;
  ramTotalMB: number;
  ts: number;
}
