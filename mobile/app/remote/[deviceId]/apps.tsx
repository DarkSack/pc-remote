import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { RemoteScaffold } from "@/ui/RemoteScaffold";
import { useConnection } from "@/stores/connection";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Apps: lista + filtro + tap para lanzar. Cache local de la lista;
// pull-to-refresh recarga con refresh=true en el server.
// ══════════════════════════════════════════════════════════════

interface App { id: string; name: string; source: string }

export default function AppsScreen() {
  const manager = useConnection((s) => s.manager);
  const [apps,    setApps]    = useState<App[]>([]);
  const [loading, setLoading] = useState(true);
  const [query,   setQuery]   = useState("");
  const [launching, setLaunching] = useState<string | null>(null);

  const load = async (refresh = false) => {
    if (!manager) return;
    setLoading(true);
    try {
      const res = await manager.request<{ applications: App[] }>("applications", "list", refresh ? { refresh: true } : undefined);
      setApps(res.applications);
    } catch { /* silent */ } finally { setLoading(false); }
  };

  useEffect(() => { void load(false); }, [manager]);

  const filtered = useMemo(() => {
    if (!query) return apps;
    const q = query.toLowerCase();
    return apps.filter((a) => a.name.toLowerCase().includes(q));
  }, [apps, query]);

  async function launch(app: App) {
    if (!manager) return;
    setLaunching(app.id);
    try { await manager.request("applications", "launch", { id: app.id }); }
    catch { /* silent */ } finally { setLaunching(null); }
  }

  return (
    <RemoteScaffold title="Aplicaciones">
      <View style={styles.container}>
        <View style={styles.searchRow}>
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder="Buscar…"
            placeholderTextColor={colors.textMuted}
            style={styles.search}
            autoCapitalize="none"
            autoCorrect={false}
          />
          <Pressable style={styles.refreshBtn} onPress={() => load(true)}>
            <Text style={styles.refreshBtnText}>↻</Text>
          </Pressable>
        </View>
        <Text style={styles.count}>{filtered.length} de {apps.length}</Text>

        {loading && apps.length === 0 ? (
          <View style={styles.centerBox}><ActivityIndicator color={colors.accent} /></View>
        ) : (
          <FlatList
            data={filtered}
            keyExtractor={(a) => a.id}
            contentContainerStyle={{ paddingBottom: spacing.xl }}
            renderItem={({ item }) => (
              <Pressable
                style={[styles.item, launching === item.id && styles.itemBusy]}
                onPress={() => launch(item)}
                disabled={launching !== null}
              >
                <View style={{ flex: 1 }}>
                  <Text style={styles.itemName} numberOfLines={1}>{item.name}</Text>
                  <Text style={styles.itemSource}>{item.source}</Text>
                </View>
                {launching === item.id
                  ? <ActivityIndicator color={colors.accent} size="small" />
                  : <Text style={styles.launch}>▶</Text>}
              </Pressable>
            )}
          />
        )}
      </View>
    </RemoteScaffold>
  );
}

const styles = StyleSheet.create({
  container:      { flex: 1, padding: spacing.md, gap: spacing.sm },
  searchRow:      { flexDirection: "row", gap: spacing.sm, alignItems: "center" },
  search:         { flex: 1, backgroundColor: colors.card, color: colors.text, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, padding: spacing.md, fontSize: 15 },
  refreshBtn:     { width: 44, height: 44, borderRadius: radius.md, backgroundColor: colors.card, borderWidth: 1, borderColor: colors.border, alignItems: "center", justifyContent: "center" },
  refreshBtnText: { color: colors.accent, fontSize: 20, fontWeight: "700" },
  count:          { color: colors.textDim, fontSize: 11, paddingHorizontal: 4 },
  centerBox:      { flex: 1, alignItems: "center", justifyContent: "center" },
  item:           { flexDirection: "row", alignItems: "center", backgroundColor: colors.card, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: spacing.md, marginBottom: 6, gap: spacing.md },
  itemBusy:       { opacity: 0.5 },
  itemName:       { color: colors.text, fontSize: 15, fontWeight: "600" },
  itemSource:     { color: colors.textMuted, fontSize: 11, marginTop: 2 },
  launch:         { color: colors.accent, fontSize: 18, fontWeight: "700" },
});
