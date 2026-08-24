import { create } from "zustand";
import type { ConnectionState } from "@/net/connection";
import { ConnectionManager } from "@/net/connection";
import type { AgentCredentials } from "@/storage/secure";
import type { SystemStats } from "@/net/protocol";

interface ConnectionStore {
  state: ConnectionState;
  error: string | null;
  currentAgentId: string | null;
  manager: ConnectionManager | null;
  stats: SystemStats | null;

  connectTo: (creds: AgentCredentials) => void;
  disconnect: () => void;
  setStats: (s: SystemStats | null) => void;
}

export const useConnection = create<ConnectionStore>((set, get) => ({
  state: "disconnected",
  error: null,
  currentAgentId: null,
  manager: null,
  stats: null,

  connectTo: (creds) => {
    get().manager?.disconnect();
    const mgr = new ConnectionManager(creds);
    mgr.addListener({
      onState: (state, error) => set({ state, error: error ?? null }),
    });
    set({ manager: mgr, currentAgentId: creds.deviceId, state: "connecting", error: null, stats: null });
    mgr.connect();
  },

  disconnect: () => {
    get().manager?.disconnect();
    set({ manager: null, currentAgentId: null, state: "disconnected", stats: null });
  },

  setStats: (stats) => set({ stats }),
}));
