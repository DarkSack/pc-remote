import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: "#0f172a" },
          headerTintColor: "#e6edf3",
          contentStyle: { backgroundColor: "#0f172a" },
        }}
      >
        <Stack.Screen name="index" options={{ title: "PC Remote" }} />
        <Stack.Screen name="pair/[host]" options={{ title: "Emparejar" }} />
        <Stack.Screen name="dashboard/[deviceId]" options={{ headerShown: false }} />
        <Stack.Screen name="remote/[deviceId]"    options={{ headerShown: false }} />
      </Stack>
    </SafeAreaProvider>
  );
}
