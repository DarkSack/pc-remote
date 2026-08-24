// ══════════════════════════════════════════════════════════════
// Store global de la conexión activa. Solo una a la vez.
// Todas las pantallas del dashboard (touchpad, media, apps, etc.)
// comparten el mismo ConnectionManager desde aquí — no crean el suyo.
// ══════════════════════════════════════════════════════════════

import { create } from "zustand";
import type { ConnectionState } from "@/net/connection";
import { ConnectionManager } from "@/net/connection";
import { loadCredentials } from "@/storage/secure";

interface ConnectionStore {
  state: ConnectionState;
  error: string | null;
  currentAgentId: string | null;
  agentName: string | null;
  manager: ConnectionManager | null;

  /** Conecta al device si no está ya conectado. Idempotente. */
  ensureConnected: (deviceId: string) => Promise<void>;
  disconnect: () => void;
}

export const useConnection = create<ConnectionStore>((set, get) => ({
  state: "disconnected",
  error: null,
  currentAgentId: null,
  agentName: null,
  manager: null,

  ensureConnected: async (deviceId) => {
    const cur = get();
    if (cur.currentAgentId === deviceId && cur.manager) return;
    cur.manager?.disconnect();

    const creds = await loadCredentials(deviceId);
    if (!creds) { set({ error: "No credentials", state: "failed" }); return; }

    const mgr = new ConnectionManager(creds);
    mgr.addListener({
      onState: (state, error) => set({ state, error: error ?? null }),
    });
    set({
      manager: mgr,
      currentAgentId: deviceId,
      agentName: creds.agentName,
      state: "connecting",
      error: null,
    });
    mgr.connect();
  },

  disconnect: () => {
    get().manager?.disconnect();
    set({ manager: null, currentAgentId: null, agentName: null, state: "disconnected", error: null });
  },
}));
