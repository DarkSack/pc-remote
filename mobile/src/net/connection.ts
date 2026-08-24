// TODO Phase 3: máquina de estados + WebSocket real.
//
//   DISCONNECTED → DISCOVERING → PAIRING → CONNECTING → CONNECTED
//                                                ↓
//                                          RECONNECTING → CONNECTED
//                                                ↓
//                                             FAILED
//
// Backoff exponencial: 1s → 2s → 4s → 8s → 16s → 30s (tope).
// Ping cada 15s. Timeout 5s → RECONNECTING.

export type ConnectionState =
  | "disconnected"
  | "discovering"
  | "pairing"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "failed";

export interface ConnectionOptions {
  host: string;
  port: number;
  deviceId: string;
  certFingerprint: string;
}
