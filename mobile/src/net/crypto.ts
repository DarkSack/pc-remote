// ══════════════════════════════════════════════════════════════
// Ed25519 keygen + sign. La private key vive solo en el móvil
// (Android Keystore vía expo-secure-store).
// ══════════════════════════════════════════════════════════════

import "react-native-get-random-values"; // polyfill de crypto.getRandomValues
import * as ed from "@noble/ed25519";

export interface Ed25519KeyPair {
  privateKey: Uint8Array; // 32 bytes seed
  publicKey:  Uint8Array; // 32 bytes
}

/** Genera un nuevo keypair Ed25519. */
export async function generateKeypair(): Promise<Ed25519KeyPair> {
  const privateKey = new Uint8Array(32);
  crypto.getRandomValues(privateKey);
  const publicKey = await ed.getPublicKeyAsync(privateKey);
  return { privateKey, publicKey };
}

/** Firma un mensaje con la private key. Devuelve base64. */
export async function signBase64(privateKey: Uint8Array, message: Uint8Array): Promise<string> {
  const sig = await ed.signAsync(message, privateKey);
  return bytesToBase64(sig);
}

// ── Codificaciones ──────────────────────────────────────

export function bytesToBase64(bytes: Uint8Array): string {
  // React Native tiene btoa. En Node tests usar Buffer si existe.
  if (typeof btoa === "function") {
    let bin = "";
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  }
  // Fallback Node
  return globalThis.Buffer.from(bytes).toString("base64");
}

export function base64ToBytes(b64: string): Uint8Array {
  if (typeof atob === "function") {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  return new Uint8Array(globalThis.Buffer.from(b64, "base64"));
}

export function hexToBytes(hex: string): Uint8Array {
  const clean = hex.length % 2 === 0 ? hex : "0" + hex;
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < clean.length; i += 2) {
    out[i / 2] = parseInt(clean.slice(i, i + 2), 16);
  }
  return out;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
