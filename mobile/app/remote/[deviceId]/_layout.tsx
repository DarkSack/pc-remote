import { Stack } from "expo-router";
import { useEffect } from "react";
import { useLocalSearchParams } from "expo-router";
import { useConnection } from "@/stores/connection";

// Asegura que la conexión al device está viva mientras el usuario
// navega entre sub-pantallas de control.
export default function RemoteLayout() {
  const { deviceId } = useLocalSearchParams<{ deviceId: string }>();
  const ensure = useConnection((s) => s.ensureConnected);
  useEffect(() => { void ensure(deviceId); }, [deviceId, ensure]);
  return (
    <Stack
      screenOptions={{
        headerStyle: { backgroundColor: "#0f172a" },
        headerTintColor: "#e6edf3",
        contentStyle: { backgroundColor: "#0f172a" },
      }}
    >
      <Stack.Screen name="touchpad"  options={{ title: "Touchpad" }} />
      <Stack.Screen name="keyboard"  options={{ title: "Teclado" }} />
      <Stack.Screen name="media"     options={{ title: "Media" }} />
      <Stack.Screen name="apps"      options={{ title: "Apps" }} />
      <Stack.Screen name="clipboard" options={{ title: "Portapapeles" }} />
    </Stack>
  );
}
