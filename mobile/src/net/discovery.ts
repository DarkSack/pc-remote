// ══════════════════════════════════════════════════════════════
// mDNS discovery vía react-native-zeroconf.
// El agente publica `_pcremote._tcp` con TXT { hostname, os, version }.
// ══════════════════════════════════════════════════════════════

import Zeroconf from "react-native-zeroconf";

export interface DiscoveredAgent {
  id: string;         // deviceId si ya emparejado (via cache), sino hostname
  name: string;
  host: string;       // IP o hostname .local
  port: number;
  os?: string;
  version?: string;
}

const SERVICE_TYPE = "pcremote";
const PROTOCOL     = "tcp";

export type DiscoveryListener = (agent: DiscoveredAgent) => void;

class DiscoveryManager {
  private zeroconf = new Zeroconf();
  private started = false;
  private listeners = new Set<DiscoveryListener>();

  start(): void {
    if (this.started) return;
    this.started = true;
    this.zeroconf.on("resolved", (svc: any) => {
      const agent: DiscoveredAgent = {
        id:      svc.txt?.hostname ?? svc.name,
        name:    svc.name,
        host:    svc.host || svc.addresses?.[0] || "",
        port:    svc.port,
        os:      svc.txt?.os,
        version: svc.txt?.version,
      };
      if (!agent.host || !agent.port) return;
      for (const l of this.listeners) l(agent);
    });
    this.zeroconf.scan(SERVICE_TYPE, PROTOCOL);
  }

  stop(): void {
    if (!this.started) return;
    this.zeroconf.stop();
    this.started = false;
  }

  onAgent(listener: DiscoveryListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export const discovery = new DiscoveryManager();
