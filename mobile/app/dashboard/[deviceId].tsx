import { useEffect, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ConnectionManager, type ConnectionState } from "@/net/connection";
import { deleteCredentials, loadCredentials } from "@/storage/secure";
import type { SystemInfo, SystemStats } from "@/net/protocol";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Dashboard por dispositivo. Al montar:
//   - Carga credenciales.
//   - Construye ConnectionManager y conecta.
//   - Cuando llega a `connected`, request system.info y subscribe systeminfo.stats.
//   - Al desmontar, disconnect().
// ══════════════════════════════════════════════════════════════

export default function DashboardScreen() {
  const router = useRouter();
  const { deviceId } = useLocalSearchParams<{ deviceId: string }>();

  const [state, setState] = useState<ConnectionState>("disconnected");
  const [error, setError] = useState<string | null>(null);
  const [info,  setInfo]  = useState<SystemInfo | null>(null);
  const [stats, setStats] = useState<SystemStats | null>(null);
  const [mgr,   setMgr]   = useState<ConnectionManager | null>(null);
  const [busy,  setBusy]  = useState(false);
  const [agentName, setAgentName] = useState<string>("");

  useEffect(() => {
    let manager: ConnectionManager | null = null;
    let subHandle: { unsubscribe: () => void } | null = null;
    let disposed = false;

    (async () => {
      const creds = await loadCredentials(deviceId);
      if (!creds || disposed) { setError("No credentials for this device"); return; }
      setAgentName(creds.agentName);
      manager = new ConnectionManager(creds);
      manager.addListener({
        onState: async (s, e) => {
          setState(s);
          setError(e ?? null);
          if (s === "connected" && manager) {
            try {
              const i = await manager.request<SystemInfo>("system", "info");
              setInfo(i);
            } catch (err) { setError((err as Error).message); }
            subHandle?.unsubscribe();
            subHandle = manager.subscribe(
              "systeminfo", "stats",
              (data) => setStats(data as SystemStats),
              { intervalMs: 1000 },
            );
          }
        },
      });
      setMgr(manager);
      manager.connect();
    })();

    return () => {
      disposed = true;
      subHandle?.unsubscribe();
      manager?.disconnect();
    };
  }, [deviceId]);

  async function runDestructive(action: "shutdown" | "restart" | "lock" | "sleep" | "logoff") {
    if (!mgr) return;
    const labels: Record<string, string> = {
      shutdown: "apagar", restart: "reiniciar", lock: "bloquear", sleep: "suspender", logoff: "cerrar sesión en",
    };
    const label = labels[action];
    const isDestructive = action !== "lock";
    if (isDestructive) {
      const ok = await confirmAsync(`¿${label[0].toUpperCase() + label.slice(1)} el PC?`, `Esta acción es inmediata.`);
      if (!ok) return;
    }
    try {
      setBusy(true);
      await mgr.request("system", action);
    } catch (e) { Alert.alert("Error", (e as Error).message); }
    finally { setBusy(false); }
  }

  async function unpair() {
    const ok = await confirmAsync("¿Desemparejar?", "Se borrarán las credenciales de este dispositivo.");
    if (!ok) return;
    mgr?.disconnect();
    await deleteCredentials(deviceId);
    router.replace("/");
  }

  const statusColor =
    state === "connected"      ? colors.success :
    state === "authenticating" ? colors.warn :
    state === "failed"         ? colors.danger :
    colors.textDim;

  return (
    <SafeAreaView style={styles.safe} edges={["top", "bottom"]}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.headerRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.title}>{info?.hostname ?? agentName ?? "PC"}</Text>
            <View style={styles.statusRow}>
              <View style={[styles.dot, { backgroundColor: statusColor }]} />
              <Text style={styles.statusText}>{stateLabel(state)}</Text>
            </View>
            {error && <Text style={styles.errorText}>{error}</Text>}
          </View>
          <Pressable style={styles.iconBtn} onPress={() => router.replace("/")}>
            <Text style={styles.iconBtnText}>‹</Text>
          </Pressable>
        </View>

        {/* Stats tiles */}
        <View style={styles.tiles}>
          <Tile label="CPU"     value={stats ? `${stats.cpu.toFixed(0)}%` : "—"} />
          <Tile label="RAM"     value={stats ? `${stats.ramPct.toFixed(0)}%` : "—"}
                sub={stats ? `${(stats.ramUsedMB / 1024).toFixed(1)} / ${(stats.ramTotalMB / 1024).toFixed(1)} GB` : undefined} />
        </View>

        {/* System info */}
        {info && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Sistema</Text>
            <InfoRow k="Usuario"  v={info.username} />
            <InfoRow k="OS"       v={`${info.os} (${info.osBuild})`} />
            <InfoRow k="CPU"      v={`${info.cpuModel} · ${info.cpuCores} cores`} />
            <InfoRow k="RAM tot." v={`${(info.ramTotalMB / 1024).toFixed(1)} GB`} />
            <InfoRow k="Uptime"   v={formatUptime(info.uptimeSec)} />
            <InfoRow k="Zona"     v={info.timezone} />
          </View>
        )}

        {/* Power controls */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Power</Text>
          <View style={styles.actions}>
            <ActionBtn label="Bloquear"  onPress={() => runDestructive("lock")}     disabled={busy || state !== "connected"} />
            <ActionBtn label="Suspender" onPress={() => runDestructive("sleep")}    disabled={busy || state !== "connected"} tone="warn" />
            <ActionBtn label="Log off"   onPress={() => runDestructive("logoff")}   disabled={busy || state !== "connected"} tone="warn" />
            <ActionBtn label="Reiniciar" onPress={() => runDestructive("restart")}  disabled={busy || state !== "connected"} tone="danger" />
            <ActionBtn label="Apagar"    onPress={() => runDestructive("shutdown")} disabled={busy || state !== "connected"} tone="danger" />
          </View>
        </View>

        <Pressable style={styles.unpair} onPress={unpair}>
          <Text style={styles.unpairText}>Desemparejar dispositivo</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

// ── Subcomponentes ─────────────────────────────────────────────
function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <View style={styles.tile}>
      <Text style={styles.tileLabel}>{label}</Text>
      <Text style={styles.tileValue}>{value}</Text>
      {sub && <Text style={styles.tileSub}>{sub}</Text>}
    </View>
  );
}

function InfoRow({ k, v }: { k: string; v: string }) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoKey}>{k}</Text>
      <Text style={styles.infoVal} numberOfLines={2}>{v}</Text>
    </View>
  );
}

function ActionBtn({ label, onPress, disabled, tone = "default" }:
  { label: string; onPress: () => void; disabled?: boolean; tone?: "default" | "warn" | "danger" }) {
  const bg = tone === "danger" ? colors.danger : tone === "warn" ? colors.warn : colors.accentDim;
  return (
    <Pressable
      style={[styles.actionBtn, { backgroundColor: bg }, disabled && { opacity: 0.35 }]}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={styles.actionBtnText}>{label}</Text>
    </Pressable>
  );
}

// ── Utils ──────────────────────────────────────────────────────
function stateLabel(s: ConnectionState): string {
  return {
    disconnected:   "Desconectado",
    connecting:     "Conectando…",
    authenticating: "Autenticando…",
    connected:      "Conectado",
    reconnecting:   "Reconectando…",
    failed:         "Falló",
  }[s];
}

function formatUptime(sec: number): string {
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const parts: string[] = [];
  if (d) parts.push(`${d}d`);
  if (h) parts.push(`${h}h`);
  parts.push(`${m}m`);
  return parts.join(" ");
}

function confirmAsync(title: string, message: string): Promise<boolean> {
  return new Promise((resolve) => {
    Alert.alert(title, message, [
      { text: "Cancelar", style: "cancel", onPress: () => resolve(false) },
      { text: "Sí", style: "destructive", onPress: () => resolve(true) },
    ]);
  });
}

const styles = StyleSheet.create({
  safe:        { flex: 1, backgroundColor: colors.bg },
  scroll:      { padding: spacing.lg, gap: spacing.lg },
  headerRow:   { flexDirection: "row", alignItems: "flex-start" },
  title:       { color: colors.text, fontSize: 24, fontWeight: "800" },
  statusRow:   { flexDirection: "row", alignItems: "center", gap: 6, marginTop: 4 },
  dot:         { width: 8, height: 8, borderRadius: 4 },
  statusText:  { color: colors.textDim, fontSize: 13 },
  errorText:   { color: colors.danger, fontSize: 12, marginTop: 4 },
  iconBtn:     { padding: spacing.sm, marginTop: -4 },
  iconBtnText: { color: colors.accent, fontSize: 32, lineHeight: 32 },

  tiles:       { flexDirection: "row", gap: spacing.md },
  tile:        { flex: 1, backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.lg, borderWidth: 1, borderColor: colors.border },
  tileLabel:   { color: colors.textDim, fontSize: 12, fontWeight: "600", textTransform: "uppercase", letterSpacing: 1 },
  tileValue:   { color: colors.text, fontSize: 32, fontWeight: "800", marginTop: 4, fontVariant: ["tabular-nums"] },
  tileSub:     { color: colors.textMuted, fontSize: 11, marginTop: 2 },

  card:        { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.lg, borderWidth: 1, borderColor: colors.border },
  cardTitle:   { color: colors.text, fontSize: 13, fontWeight: "700", textTransform: "uppercase", letterSpacing: 1, marginBottom: spacing.md },
  infoRow:     { flexDirection: "row", justifyContent: "space-between", paddingVertical: 4, gap: spacing.md },
  infoKey:     { color: colors.textDim, fontSize: 13, minWidth: 70 },
  infoVal:     { color: colors.text, fontSize: 13, flex: 1, textAlign: "right" },

  actions:     { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
  actionBtn:   { paddingHorizontal: spacing.lg, paddingVertical: spacing.md, borderRadius: radius.md, minWidth: 100, alignItems: "center" },
  actionBtnText: { color: "#0f172a", fontSize: 14, fontWeight: "700" },

  unpair:      { padding: spacing.lg, alignItems: "center" },
  unpairText:  { color: colors.danger, fontSize: 13, fontWeight: "600" },
});
