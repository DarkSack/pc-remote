import { useEffect, useRef, useState } from "react";
import { StyleSheet, Text, TextInput, View, Pressable, ActivityIndicator } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { PairingSession, type PairPhase } from "@/net/pairing";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Pairing screen: abre WS al agente, muestra input de 6 dígitos que
// aparece en la notificación del tray de Windows, y guarda las
// credenciales en secure store al completar.
// ══════════════════════════════════════════════════════════════

export default function PairScreen() {
  const router = useRouter();
  const { host, port, name } = useLocalSearchParams<{ host: string; port: string; name: string }>();
  const [phase, setPhase] = useState<PairPhase>("connecting");
  const [info, setInfo]   = useState<string>("");
  const [code, setCode]   = useState("");
  const [deviceName, setDeviceName] = useState("Android");
  const sessionRef = useRef<PairingSession | null>(null);

  useEffect(() => {
    const session = new PairingSession(
      { host, port: Number(port), agentName: name },
      (p, i) => { setPhase(p); if (i) setInfo(i); },
    );
    sessionRef.current = session;
    void session.start();
    return () => session.cancel();
  }, [host, port, name]);

  useEffect(() => {
    if (phase === "done") {
      const t = setTimeout(() => router.replace("/"), 800);
      return () => clearTimeout(t);
    }
  }, [phase, router]);

  const canSubmit = phase === "waiting_code" && code.length === 6 && deviceName.trim().length > 0;

  return (
    <View style={styles.container}>
      <Text style={styles.header}>Emparejando con</Text>
      <Text style={styles.host}>{name}</Text>
      <Text style={styles.sub}>{host}:{port}</Text>

      <View style={styles.body}>
        {phase === "connecting" && (
          <View style={styles.center}>
            <ActivityIndicator size="large" color={colors.accent} />
            <Text style={styles.status}>Conectando al agente…</Text>
          </View>
        )}

        {phase === "waiting_code" && (
          <>
            <Text style={styles.helper}>
              Mira la notificación en el escritorio del PC. Introduce el código de 6 dígitos:
            </Text>
            {info ? <Text style={styles.ttl}>{info}</Text> : null}
            <TextInput
              value={code}
              onChangeText={(t) => setCode(t.replace(/[^0-9]/g, "").slice(0, 6))}
              keyboardType="number-pad"
              placeholder="000000"
              placeholderTextColor={colors.textMuted}
              style={styles.codeInput}
              maxLength={6}
              autoFocus
            />
            <Text style={styles.helperSmall}>Nombre visible en el PC:</Text>
            <TextInput
              value={deviceName}
              onChangeText={setDeviceName}
              placeholder="Android"
              placeholderTextColor={colors.textMuted}
              style={styles.textInput}
              maxLength={32}
            />
            <Pressable
              disabled={!canSubmit}
              style={[styles.btn, !canSubmit && styles.btnDisabled]}
              onPress={() => sessionRef.current?.submitCode(code, deviceName.trim())}
            >
              <Text style={styles.btnText}>Confirmar</Text>
            </Pressable>
          </>
        )}

        {phase === "confirming" && (
          <View style={styles.center}>
            <ActivityIndicator size="large" color={colors.accent} />
            <Text style={styles.status}>Verificando código…</Text>
          </View>
        )}

        {phase === "done" && (
          <View style={styles.center}>
            <Text style={[styles.status, { color: colors.success }]}>✓ Emparejado</Text>
            <Text style={styles.sub}>Volviendo a la lista…</Text>
          </View>
        )}

        {phase === "error" && (
          <View style={styles.center}>
            <Text style={[styles.status, { color: colors.danger }]}>Error de emparejamiento</Text>
            <Text style={styles.sub}>{info || "Sin detalles"}</Text>
            <Pressable style={styles.btn} onPress={() => router.replace("/")}>
              <Text style={styles.btnText}>Volver</Text>
            </Pressable>
          </View>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container:   { flex: 1, backgroundColor: colors.bg, padding: spacing.xl },
  header:      { color: colors.textDim, fontSize: 14 },
  host:        { color: colors.text, fontSize: 22, fontWeight: "800", marginTop: 4 },
  sub:         { color: colors.textDim, fontSize: 13, marginTop: 2 },
  body:        { marginTop: spacing.xxl, gap: spacing.md },
  center:      { alignItems: "center", gap: spacing.md, paddingVertical: spacing.xxl },
  status:      { color: colors.text, fontSize: 16, fontWeight: "600" },
  helper:      { color: colors.textDim, fontSize: 14, marginBottom: spacing.md, lineHeight: 20 },
  helperSmall: { color: colors.textDim, fontSize: 12, marginTop: spacing.md },
  ttl:         { color: colors.warn, fontSize: 12, marginBottom: spacing.sm },
  codeInput:   {
    backgroundColor: colors.card, color: colors.text, fontSize: 32, letterSpacing: 8,
    textAlign: "center", padding: spacing.lg, borderRadius: radius.md,
    borderWidth: 1, borderColor: colors.border, fontVariant: ["tabular-nums"],
  },
  textInput:   {
    backgroundColor: colors.card, color: colors.text, fontSize: 16,
    padding: spacing.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border,
  },
  btn:         {
    backgroundColor: colors.accentDim, padding: spacing.lg, borderRadius: radius.md,
    alignItems: "center", marginTop: spacing.md,
  },
  btnDisabled: { opacity: 0.4 },
  btnText:     { color: "#fff", fontSize: 16, fontWeight: "700" },
});
