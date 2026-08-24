import { useEffect, useState } from "react";
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useRouter } from "expo-router";
import { discovery, type DiscoveredAgent } from "@/net/discovery";
import { listPairedDeviceIds, loadCredentials, type AgentCredentials } from "@/storage/secure";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Pantalla principal:
//  - Lista dispositivos emparejados (guardados en secure store).
//  - Debajo, escanea la LAN vía mDNS y muestra los agentes descubiertos
//    que aún no están emparejados.
// ══════════════════════════════════════════════════════════════

export default function DiscoveryScreen() {
  const router = useRouter();
  const [paired, setPaired] = useState<AgentCredentials[]>([]);
  const [discovered, setDiscovered] = useState<DiscoveredAgent[]>([]);
  const [scanning, setScanning] = useState(false);

  useEffect(() => {
    void refreshPaired();
    const off = discovery.onAgent((a) => {
      setDiscovered((prev) =>
        prev.find((x) => x.host === a.host && x.port === a.port) ? prev : [...prev, a],
      );
    });
    setScanning(true);
    discovery.start();
    return () => { off(); discovery.stop(); };
  }, []);

  async function refreshPaired() {
    const ids = await listPairedDeviceIds();
    const creds = await Promise.all(ids.map((id) => loadCredentials(id)));
    setPaired(creds.filter((c): c is AgentCredentials => c !== null));
  }

  // Filtra los descubiertos que ya están emparejados por host:port.
  const newAgents = discovered.filter(
    (d) => !paired.find((p) => p.agentHost === d.host && p.agentPort === d.port),
  );

  return (
    <SafeAreaView style={styles.safe} edges={["bottom"]}>
      <FlatList
        contentContainerStyle={styles.list}
        data={paired}
        keyExtractor={(item) => item.deviceId}
        ListHeaderComponent={
          <Text style={styles.section}>Dispositivos emparejados</Text>
        }
        ListEmptyComponent={
          <Text style={styles.empty}>Ningún PC emparejado todavía.</Text>
        }
        renderItem={({ item }) => (
          <Pressable
            style={styles.card}
            onPress={() => router.push({ pathname: "/dashboard/[deviceId]", params: { deviceId: item.deviceId } })}
          >
            <Text style={styles.cardTitle}>{item.agentName}</Text>
            <Text style={styles.cardSub}>{item.agentHost}:{item.agentPort}</Text>
          </Pressable>
        )}
        ListFooterComponent={
          <View style={styles.footer}>
            <View style={styles.rowBetween}>
              <Text style={styles.section}>Descubiertos en la red</Text>
              {scanning && <ActivityIndicator size="small" color={colors.accent} />}
            </View>
            {newAgents.length === 0 ? (
              <Text style={styles.empty}>Buscando agentes en tu LAN…</Text>
            ) : (
              newAgents.map((a) => (
                <Pressable
                  key={`${a.host}:${a.port}`}
                  style={styles.card}
                  onPress={() =>
                    router.push({
                      pathname: "/pair/[host]",
                      params: { host: a.host, port: String(a.port), name: a.name },
                    })
                  }
                >
                  <Text style={styles.cardTitle}>{a.name}</Text>
                  <Text style={styles.cardSub}>
                    {a.host}:{a.port}
                    {a.os ? ` · ${a.os}` : ""}
                  </Text>
                  <Text style={styles.cta}>Emparejar →</Text>
                </Pressable>
              ))
            )}
          </View>
        }
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe:        { flex: 1, backgroundColor: colors.bg },
  list:        { padding: spacing.lg, gap: spacing.md },
  section:     { color: colors.text, fontSize: 15, fontWeight: "700", marginBottom: spacing.sm, marginTop: spacing.md },
  empty:       { color: colors.textMuted, fontSize: 13, fontStyle: "italic", paddingVertical: spacing.md },
  card:        { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.lg, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.sm },
  cardTitle:   { color: colors.text, fontSize: 16, fontWeight: "700" },
  cardSub:     { color: colors.textDim, fontSize: 13, marginTop: 2 },
  cta:         { color: colors.accent, fontSize: 13, fontWeight: "600", marginTop: spacing.sm },
  footer:      { marginTop: spacing.lg },
  rowBetween:  { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
});
