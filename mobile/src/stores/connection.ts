import { create } from "zustand";
import type { ConnectionState } from "@/net/connection";

interface ConnectionStore {
  state: ConnectionState;
  currentAgentId: string | null;
  setState: (state: ConnectionState) => void;
  setCurrentAgent: (id: string | null) => void;
}

export const useConnection = create<ConnectionStore>((set) => ({
  state: "disconnected",
  currentAgentId: null,
  setState: (state) => set({ state }),
  setCurrentAgent: (id) => set({ currentAgentId: id }),
}));
