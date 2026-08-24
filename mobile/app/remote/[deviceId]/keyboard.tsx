import { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { RemoteScaffold } from "@/ui/RemoteScaffold";
import { useConnection } from "@/stores/connection";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Teclado virtual: envía texto Unicode + botones para teclas
// especiales y combos comunes (F1-F12, arrows, media, ctrl+c...).
// ══════════════════════════════════════════════════════════════

const SPECIAL_ROWS: string[][] = [
  ["esc", "tab", "enter", "backspace", "delete"],
  ["up", "down", "left", "right", "home", "end", "pageup", "pagedown"],
  ["f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8"],
  ["f9", "f10", "f11", "f12", "printscreen", "space"],
];

const COMBOS: { label: string; keys: string }[] = [
  { label: "Copiar",       keys: "ctrl+c" },
  { label: "Pegar",        keys: "ctrl+v" },
  { label: "Cortar",       keys: "ctrl+x" },
  { label: "Deshacer",     keys: "ctrl+z" },
  { label: "Rehacer",      keys: "ctrl+y" },
  { label: "Buscar",       keys: "ctrl+f" },
  { label: "Guardar",      keys: "ctrl+s" },
  { label: "Alt+Tab",      keys: "alt+tab" },
  { label: "Task Mgr",     keys: "ctrl+shift+esc" },
  { label: "Bloq. PC",     keys: "win+l" },
  { label: "Escritorio",   keys: "win+d" },
];

export default function KeyboardScreen() {
  const manager = useConnection((s) => s.manager);
  const [text, setText] = useState("");

  function send(keys: string) { manager?.request("input", "keyPress", { keys }).catch(() => {}); }

  function sendText() {
    if (!text) return;
    manager?.request("input", "keyType", { text }).catch(() => {});
    setText("");
  }

  return (
    <RemoteScaffold title="Teclado">
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Enviar texto</Text>
          <TextInput
            value={text}
            onChangeText={setText}
            placeholder="Escribe aquí…"
            placeholderTextColor={colors.textMuted}
            style={styles.input}
            multiline
            autoCapitalize="none"
            autoCorrect={false}
          />
          <Pressable style={styles.primaryBtn} onPress={sendText} disabled={!text}>
            <Text style={styles.primaryBtnText}>Enviar</Text>
          </Pressable>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Teclas especiales</Text>
          {SPECIAL_ROWS.map((row, i) => (
            <View key={i} style={styles.row}>
              {row.map((k) => (
                <Pressable key={k} style={styles.key} onPress={() => send(k)}>
                  <Text style={styles.keyText}>{k}</Text>
                </Pressable>
              ))}
            </View>
          ))}
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Combos</Text>
          <View style={styles.combos}>
            {COMBOS.map((c) => (
              <Pressable key={c.keys} style={styles.combo} onPress={() => send(c.keys)}>
                <Text style={styles.comboLabel}>{c.label}</Text>
                <Text style={styles.comboKeys}>{c.keys}</Text>
              </Pressable>
            ))}
          </View>
        </View>
      </ScrollView>
    </RemoteScaffold>
  );
}

const styles = StyleSheet.create({
  scroll:        { padding: spacing.md, gap: spacing.md },
  card:          { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.md, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.md, gap: spacing.sm },
  cardTitle:     { color: colors.text, fontSize: 12, fontWeight: "700", textTransform: "uppercase", letterSpacing: 1, marginBottom: 4 },
  input:         { backgroundColor: colors.bgAlt, color: colors.text, borderRadius: radius.sm, borderWidth: 1, borderColor: colors.border, padding: spacing.md, minHeight: 80, textAlignVertical: "top" },
  primaryBtn:    { backgroundColor: colors.accentDim, padding: spacing.md, borderRadius: radius.md, alignItems: "center" },
  primaryBtnText:{ color: "#fff", fontWeight: "700" },
  row:           { flexDirection: "row", flexWrap: "wrap", gap: 6 },
  key:           { backgroundColor: colors.bgAlt, borderWidth: 1, borderColor: colors.border, paddingHorizontal: 10, paddingVertical: 8, borderRadius: radius.sm, minWidth: 40, alignItems: "center" },
  keyText:       { color: colors.text, fontSize: 12, fontWeight: "600", fontVariant: ["tabular-nums"] },
  combos:        { flexDirection: "row", flexWrap: "wrap", gap: 6 },
  combo:         { backgroundColor: colors.bgAlt, borderWidth: 1, borderColor: colors.border, paddingHorizontal: 10, paddingVertical: 6, borderRadius: radius.sm, alignItems: "center", minWidth: 90 },
  comboLabel:    { color: colors.text, fontSize: 12, fontWeight: "600" },
  comboKeys:     { color: colors.textMuted, fontSize: 10, marginTop: 1 },
});
