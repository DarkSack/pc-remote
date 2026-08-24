// ══════════════════════════════════════════════════════════════
// Almacenamiento seguro para credenciales de dispositivos.
// - Private keys Ed25519 → expo-secure-store (Android Keystore).
// - Metadata pública (deviceId, host, cert fingerprint) → AsyncStorage/SQLite.
// ══════════════════════════════════════════════════════════════

import * as SecureStore from "expo-secure-store";

export interface AgentCredentials {
  deviceId: string;         // asignado por el agente en pair_result
  privateKeyB64: string;    // Ed25519 32 bytes (base64)
  publicKeyB64: string;     // Ed25519 32 bytes (base64)
  agentHost: string;
  agentPort: number;
  agentName: string;        // hostname del PC
  certFingerprint: string;  // SHA-256 hex — para pinning
}

const KEY_PREFIX = "pcremote_creds_"; // + deviceId

export async function saveCredentials(c: AgentCredentials): Promise<void> {
  await SecureStore.setItemAsync(KEY_PREFIX + c.deviceId, JSON.stringify(c));
  await appendToIndex(c.deviceId);
}

export async function loadCredentials(deviceId: string): Promise<AgentCredentials | null> {
  const raw = await SecureStore.getItemAsync(KEY_PREFIX + deviceId);
  return raw ? (JSON.parse(raw) as AgentCredentials) : null;
}

export async function deleteCredentials(deviceId: string): Promise<void> {
  await SecureStore.deleteItemAsync(KEY_PREFIX + deviceId);
  await removeFromIndex(deviceId);
}

export async function listPairedDeviceIds(): Promise<string[]> {
  const raw = await SecureStore.getItemAsync("pcremote_index");
  return raw ? (JSON.parse(raw) as string[]) : [];
}

async function appendToIndex(deviceId: string): Promise<void> {
  const ids = await listPairedDeviceIds();
  if (!ids.includes(deviceId)) {
    ids.push(deviceId);
    await SecureStore.setItemAsync("pcremote_index", JSON.stringify(ids));
  }
}

async function removeFromIndex(deviceId: string): Promise<void> {
  const ids = (await listPairedDeviceIds()).filter((id) => id !== deviceId);
  await SecureStore.setItemAsync("pcremote_index", JSON.stringify(ids));
}
