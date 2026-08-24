// ══════════════════════════════════════════════════════════════════
// Tipos del protocolo WebSocket. Espejo de PcRemote.Core.Protocol.
// Ver docs/PROTOCOL.md para el spec completo.
// ══════════════════════════════════════════════════════════════════

export type MessageKind =
  | "request"
  | "response"
  | "subscribe"
  | "stream"
  | "unsubscribe"
  | "event"
  | "ping"
  | "pong"
  | "pair_init"
  | "pair_confirm"
  | "auth_challenge"
  | "auth";

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

export interface UnsubscribeMessage extends BaseMessage {
  kind: "unsubscribe";
  id: string;
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
  | "RATE_LIMITED";

// ── Bootstrap (pairing / auth) ────────────────────────────────────

export interface PairInitMessage extends BaseMessage {
  kind: "pair_init";
}

export interface PairConfirmMessage extends BaseMessage {
  kind: "pair_confirm";
  code: string;
  deviceName: string;
  publicKey: string; // base64 Ed25519 public key (32 bytes)
}

export interface AuthChallengeMessage extends BaseMessage {
  kind: "auth_challenge";
  nonce: string; // hex 32 bytes
}

export interface AuthMessage extends BaseMessage {
  kind: "auth";
  deviceId: string;
  signature: string; // base64 Ed25519(nonce)
}
