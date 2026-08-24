// TODO Phase 3: mDNS con react-native-zeroconf + fallback UDP broadcast.
// Publicar servicio `_pcremote._tcp` desde el agente y suscribirse aquí.

export interface DiscoveredAgent {
  id: string; // TXT record `deviceId` si ya emparejado, sino hostname
  name: string;
  host: string;
  port: number;
  os?: string;
  version?: string;
  pairingRequired: boolean;
}
