import { Alert } from "react-native";

export function stateLabel(s: string): string {
  return {
    disconnected:   "Desconectado",
    connecting:     "Conectando…",
    authenticating: "Autenticando…",
    connected:      "Conectado",
    reconnecting:   "Reconectando…",
    failed:         "Falló",
  }[s] ?? s;
}

export function confirmAsync(title: string, message: string): Promise<boolean> {
  return new Promise((resolve) => {
    Alert.alert(title, message, [
      { text: "Cancelar", style: "cancel", onPress: () => resolve(false) },
      { text: "Sí", style: "destructive", onPress: () => resolve(true) },
    ]);
  });
}
