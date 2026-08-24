import { useEffect, useRef, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useConnection } from "@/stores/connection";
import { deleteCredentials } from "@/storage/secure";
import type { SystemInfo, SystemStats } from "@/net/protocol";
import { colors, radius, spacing } from "@/ui/theme";
import { confirmAsync, stateLabel } from "@/ui/utils";

// ══════════════════════════════════════════════════════════════
// Dashboard: conexión (via store compartido) + stats en tiempo real
// + info sistema + power controls + navegación a sub-pantallas.
// ══════════════════════════════════════════════════════════════

export default function DashboardScreen() {
  const router = useRouter();
  const { deviceId } = useLocalSearchParams<{ deviceId: string }>();

  const { state, error, agentName, manager, ensureConnected, disconnect } = useConnection();
  const [info,  setInfo]  = useState<SystemInfo | null>(null);
  const [stats, setStats] = useState<SystemStats | null>(null);
  const [busy,  setBusy]  = useState(false);
  const statsSubRef = useRef<{ unsubscribe: () => void } | null>(null);

  useEffect(() => { void ensureConnected(deviceId); }, [deviceId, ensureConnected]);

  useEffect(() => {
    if (state !== "connected" || !manager) return;
    let cancelled = false;
    (async () => {
      try {
        const i = await manager.request<SystemInfo>("system", "info");
        if (!cancelled) setInfo(i);
      } catch { /* silent */ }
    })();
    statsSubRef.current?.unsubscribe();
    statsSubRef.current = manager.subscribe(
      "systeminfo", "stats",
      (d) => setStats(d as SystemStats),
      { intervalMs: 1000 },
    );
    return () => {
      cancelled = true;
      statsSubRef.current?.unsubscribe();
      statsSubRef.current = null;
    };
  }, [state, manager]);

  async function runPower(action: "shutdown" | "restart" | "lock" | "sleep" | "logoff") {
    if (!manager) return;
    const label = { shutdown: "apagar", restart: "reiniciar", lock: "bloquear", sleep: "suspender", logoff: "cerrar sesión en" }[action];
    if (action !== "lock" && !(await confirmAsync(`¿${cap(label)} el PC?`, "Esta acción es inmediata."))) return;
    try { setBusy(true); await manager.request("system", action); }
    catch (e) { Alert.alert("Error", (e as Error).message); }
    finally { setBusy(false); }
  }

  async function unpair() {
    if (!(await confirmAsync("¿Desemparejar?", "Se borrarán las credenciales."))) return;
    disconnect();
    await deleteCredentials(deviceId);
    router.replace("/");
  }

  const dotColor =
    state === "connected"      ? colors.success :
    state === "authenticating" ? colors.warn :
    state === "failed"         ? colors.danger :
    colors.textDim;

  const canRemote = state === "connected";

  return (
    <SafeAreaView style={styles.safe} edges={["top", "bottom"]}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.headerRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.title}>{info?.hostname ?? agentName ?? "PC"}</Text>
            <View style={styles.statusRow}>
              <View style={[styles.dot, { backgroundColor: dotColor }]} />
              <Text style={styles.statusText}>{stateLabel(state)}</Text>
            </View>
            {error && <Text style={styles.errorText}>{error}</Text>}
          </View>
          <Pressable style={styles.iconBtn} onPress={() => router.replace("/")}>
            <Text style={styles.iconBtnText}>‹</Text>
          </Pressable>
        </View>

        <View style={styles.tiles}>
          <Tile label="CPU" value={stats ? `${stats.cpu.toFixed(0)}%` : "—"} />
          <Tile label="RAM" value={stats ? `${stats.ramPct.toFixed(0)}%` : "—"}
                sub={stats ? `${(stats.ramUsedMB/1024).toFixed(1)} / ${(stats.ramTotalMB/1024).toFixed(1)} GB` : undefined} />
        </View>

        {/* Grid de control remoto → sub-pantallas */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Control</Text>
          <View style={styles.grid}>
            <GridBtn icon="🖱️"  label="Touchpad"  onPress={() => router.push(`/remote/${deviceId}/touchpad`)}  disabled={!canRemote} />
            <GridBtn icon="⌨️"  label="Teclado"   onPress={() => router.push(`/remote/${deviceId}/keyboard`)}  disabled={!canRemote} />
            <GridBtn icon="🎵"  label="Media"     onPress={() => router.push(`/remote/${deviceId}/media`)}     disabled={!canRemote} />
            <GridBtn icon="📱"  label="Apps"      onPress={() => router.push(`/remote/${deviceId}/apps`)}      disabled={!canRemote} />
            <GridBtn icon="📋"  label="Portapapeles" onPress={() => router.push(`/remote/${deviceId}/clipboard`)} disabled={!canRemote} />
          </View>
        </View>

        {info && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Sistema</Text>
            <InfoRow k="Usuario"  v={info.username} />
            <InfoRow k="OS"       v={`${info.os} (${info.osBuild})`} />
            <InfoRow k="CPU"      v={`${info.cpuModel} · ${info.cpuCores} cores`} />
            <InfoRow k="RAM tot." v={`${(info.ramTotalMB/1024).toFixed(1)} GB`} />
            <InfoRow k="Uptime"   v={formatUptime(info.uptimeSec)} />
            <InfoRow k="Zona"     v={info.timezone} />
          </View>
        )}

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Power</Text>
          <View style={styles.actions}>
            <ActionBtn label="Bloquear"  onPress={() => runPower("lock")}     disabled={busy || !canRemote} />
            <ActionBtn label="Suspender" onPress={() => runPower("sleep")}    disabled={busy || !canRemote} tone="warn" />
            <ActionBtn label="Log off"   onPress={() => runPower("logoff")}   disabled={busy || !canRemote} tone="warn" />
            <ActionBtn label="Reiniciar" onPress={() => runPower("restart")}  disabled={busy || !canRemote} tone="danger" />
            <ActionBtn label="Apagar"    onPress={() => runPower("shutdown")} disabled={busy || !canRemote} tone="danger" />
          </View>
        </View>

        <Pressable style={styles.unpair} onPress={unpair}>
          <Text style={styles.unpairText}>Desemparejar dispositivo</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

// ── Sub-componentes ────────────────────────────────────────────
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
    <Pressable style={[styles.actionBtn, { backgroundColor: bg }, disabled && { opacity: 0.35 }]} disabled={disabled} onPress={onPress}>
      <Text style={styles.actionBtnText}>{label}</Text>
    </Pressable>
  );
}

function GridBtn({ icon, label, onPress, disabled }: { icon: string; label: string; onPress: () => void; disabled?: boolean }) {
  return (
    <Pressable style={[styles.gridBtn, disabled && { opacity: 0.35 }]} disabled={disabled} onPress={onPress}>
      <Text style={styles.gridIcon}>{icon}</Text>
      <Text style={styles.gridLabel}>{label}</Text>
    </Pressable>
  );
}

// ── Utils ──────────────────────────────────────────────────────
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

function cap(s: string): string { return s[0].toUpperCase() + s.slice(1); }

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

  grid:        { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
  gridBtn:     { width: "31%", aspectRatio: 1, backgroundColor: colors.bgAlt, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, alignItems: "center", justifyContent: "center", gap: 4 },
  gridIcon:    { fontSize: 30 },
  gridLabel:   { color: colors.text, fontSize: 12, fontWeight: "600" },

  actions:     { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
  actionBtn:   { paddingHorizontal: spacing.lg, paddingVertical: spacing.md, borderRadius: radius.md, minWidth: 100, alignItems: "center" },
  actionBtnText: { color: "#0f172a", fontSize: 14, fontWeight: "700" },

  unpair:      { padding: spacing.lg, alignItems: "center" },
  unpairText:  { color: colors.danger, fontSize: 13, fontWeight: "600" },
});
