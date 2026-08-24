import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { RemoteScaffold } from "@/ui/RemoteScaffold";
import { useConnection } from "@/stores/connection";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Clipboard: get/set + watch (stream) que actualiza en tiempo real
// cuando el PC copia algo nuevo. Historial local (últimos 10).
// ══════════════════════════════════════════════════════════════

interface ClipItem { text: string; length: number; at: number }

export default function ClipboardScreen() {
  const manager = useConnection((s) => s.manager);
  const [current, setCurrent] = useState<string>("");
  const [draft, setDraft]     = useState<string>("");
  const [history, setHistory] = useState<ClipItem[]>([]);
  const [busy, setBusy]       = useState(false);

  useEffect(() => {
    if (!manager) return;
    const sub = manager.subscribe("clipboard", "watch", (data) => {
      const d = data as { text: string; length: number };
      setCurrent(d.text);
      setHistory((h) => {
        if (h[0]?.text === d.text) return h;
        return [{ text: d.text, length: d.length, at: Date.now() }, ...h].slice(0, 10);
      });
    });
    return () => sub.unsubscribe();
  }, [manager]);

  async function push() {
    if (!draft || !manager) return;
    setBusy(true);
    try { await manager.request("clipboard", "set", { text: draft }); setDraft(""); }
    catch { /* silent */ } finally { setBusy(false); }
  }

  async function clear() {
    if (!manager) return;
    setBusy(true);
    try { await manager.request("clipboard", "clear"); }
    catch { /* silent */ } finally { setBusy(false); }
  }

  return (
    <RemoteScaffold title="Portapapeles">
      <View style={styles.container}>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Actual en el PC</Text>
          <View style={styles.currentBox}>
            {current
              ? <Text style={styles.currentText} numberOfLines={6}>{current}</Text>
              : <Text style={styles.empty}>(vacío)</Text>}
          </View>
          <Pressable style={styles.dangerBtn} onPress={clear} disabled={busy || !current}>
            <Text style={styles.dangerBtnText}>Vaciar</Text>
          </Pressable>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Enviar al portapapeles</Text>
          <TextInput
            value={draft}
            onChangeText={setDraft}
            placeholder="Texto a copiar en el PC…"
            placeholderTextColor={colors.textMuted}
            style={styles.input}
            multiline
          />
          <Pressable style={styles.primaryBtn} onPress={push} disabled={busy || !draft}>
            {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Enviar</Text>}
          </Pressable>
        </View>

        <View style={[styles.card, { flex: 1 }]}>
          <Text style={styles.cardTitle}>Historial (últimos 10)</Text>
          {history.length === 0 ? (
            <Text style={styles.empty}>Aún no se ha capturado nada.</Text>
          ) : (
            history.map((h) => (
              <Pressable key={h.at} style={styles.histItem} onPress={() => setDraft(h.text)}>
                <Text style={styles.histText} numberOfLines={2}>{h.text || "(vacío)"}</Text>
                <Text style={styles.histMeta}>{h.length} chars · tap para reusar</Text>
              </Pressable>
            ))
          )}
        </View>
      </View>
    </RemoteScaffold>
  );
}

const styles = StyleSheet.create({
  container:      { flex: 1, padding: spacing.md, gap: spacing.md },
  card:           { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.md, borderWidth: 1, borderColor: colors.border, gap: spacing.sm },
  cardTitle:      { color: colors.text, fontSize: 12, fontWeight: "700", textTransform: "uppercase", letterSpacing: 1 },
  currentBox:     { backgroundColor: colors.bgAlt, borderRadius: radius.sm, padding: spacing.md, minHeight: 60 },
  currentText:    { color: colors.text, fontSize: 14 },
  empty:          { color: colors.textMuted, fontStyle: "italic", fontSize: 13 },
  input:          { backgroundColor: colors.bgAlt, color: colors.text, borderRadius: radius.sm, borderWidth: 1, borderColor: colors.border, padding: spacing.md, minHeight: 60, textAlignVertical: "top" },
  primaryBtn:     { backgroundColor: colors.accentDim, padding: spacing.md, borderRadius: radius.md, alignItems: "center" },
  primaryBtnText: { color: "#fff", fontWeight: "700" },
  dangerBtn:      { backgroundColor: colors.danger, padding: spacing.sm, borderRadius: radius.md, alignItems: "center" },
  dangerBtnText:  { color: "#0f172a", fontWeight: "700", fontSize: 13 },
  histItem:       { backgroundColor: colors.bgAlt, padding: spacing.sm, borderRadius: radius.sm, marginTop: 4 },
  histText:       { color: colors.text, fontSize: 13 },
  histMeta:       { color: colors.textMuted, fontSize: 11, marginTop: 2 },
});
