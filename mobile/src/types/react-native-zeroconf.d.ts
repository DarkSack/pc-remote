declare module "react-native-zeroconf" {
  export interface ZeroconfService {
    name: string;
    fullName?: string;
    host?: string;
    port: number;
    addresses?: string[];
    txt?: Record<string, string>;
  }
  export default class Zeroconf {
    scan(type: string, protocol: string, domain?: string): void;
    stop(): void;
    removeDeviceListeners(): void;
    on(event: "start" | "stop" | "resolved" | "found" | "remove" | "error", cb: (svc: ZeroconfService) => void): void;
  }
}
