import type { ReactNode } from "react";
import { StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { colors, spacing } from "./theme";
import { useConnection } from "@/stores/connection";
import { stateLabel } from "./utils";

// Contenedor común para las sub-pantallas de /remote/[deviceId].
// Muestra título + estado de conexión.
export function RemoteScaffold({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  const { state, agentName } = useConnection();
  const dot = state === "connected" ? colors.success : state === "failed" ? colors.danger : colors.warn;
  return (
    <SafeAreaView style={styles.safe} edges={["bottom"]}>
      <View style={styles.header}>
        <Text style={styles.title}>{title}</Text>
        <View style={styles.row}>
          <View style={[styles.dot, { backgroundColor: dot }]} />
          <Text style={styles.sub}>{subtitle ?? `${agentName ?? "PC"} · ${stateLabel(state)}`}</Text>
        </View>
      </View>
      <View style={styles.body}>{children}</View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe:   { flex: 1, backgroundColor: colors.bg },
  header: { paddingHorizontal: spacing.lg, paddingTop: spacing.sm, paddingBottom: spacing.md, borderBottomWidth: 1, borderBottomColor: colors.border },
  title:  { color: colors.text, fontSize: 20, fontWeight: "800" },
  row:    { flexDirection: "row", alignItems: "center", gap: 6, marginTop: 4 },
  dot:    { width: 8, height: 8, borderRadius: 4 },
  sub:    { color: colors.textDim, fontSize: 12 },
  body:   { flex: 1 },
});
