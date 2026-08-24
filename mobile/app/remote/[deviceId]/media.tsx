import { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import Slider from "@react-native-community/slider";
import { RemoteScaffold } from "@/ui/RemoteScaffold";
import { useConnection } from "@/stores/connection";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Media: nowPlaying + play/pause/prev/next + volume slider + mute.
// Refresca nowPlaying cada 3s. Volume no se refresca — el usuario
// controla y el móvil es fuente de verdad optimista.
// ══════════════════════════════════════════════════════════════

interface NowPlaying { active: boolean; title?: string; artist?: string; album?: string; status?: string; source?: string }
interface VolumeState { volume: number; mute: boolean }

export default function MediaScreen() {
  const manager = useConnection((s) => s.manager);
  const [np,  setNp]  = useState<NowPlaying | null>(null);
  const [vol, setVol] = useState<VolumeState | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    if (!manager) return;
    let cancelled = false;
    const refresh = async () => {
      try {
        const [n, v] = await Promise.all([
          manager.request<NowPlaying>("media", "nowPlaying"),
          manager.request<VolumeState>("media", "volumeGet"),
        ]);
        if (!cancelled) { setNp(n); setVol(v); }
      } catch { /* silent */ }
    };
    void refresh();
    const t = setInterval(refresh, 3000);
    return () => { cancelled = true; clearInterval(t); };
  }, [manager]);

  async function control(action: "play" | "pause" | "playPause" | "next" | "previous") {
    if (!manager || pending) return;
    setPending(true);
    try { await manager.request("media", action); }
    catch { /* silent */ } finally { setPending(false); }
  }

  function setVolume(v: number) {
    setVol((prev) => prev ? { ...prev, volume: Math.round(v) } : { volume: Math.round(v), mute: false });
    manager?.request("media", "volumeSet", { volume: Math.round(v) }).catch(() => {});
  }

  function toggleMute() {
    if (!vol) return;
    const desired = !vol.mute;
    setVol({ ...vol, mute: desired });
    manager?.request("media", "volumeMute", { mute: desired }).catch(() => {});
  }

  return (
    <RemoteScaffold title="Media">
      <View style={styles.container}>
        <View style={styles.nowPlaying}>
          {np?.active ? (
            <>
              <Text style={styles.title}    numberOfLines={2}>{np.title ?? "—"}</Text>
              <Text style={styles.artist}   numberOfLines={1}>{np.artist ?? ""}</Text>
              <Text style={styles.album}    numberOfLines={1}>{np.album ?? ""}</Text>
              <Text style={styles.source}>{np.status} · {sourceName(np.source)}</Text>
            </>
          ) : (
            <Text style={styles.empty}>No hay reproducción activa</Text>
          )}
        </View>

        <View style={styles.transport}>
          <Pressable style={styles.tBtn} onPress={() => control("previous")}>
            <Text style={styles.tIcon}>⏮</Text>
          </Pressable>
          <Pressable style={[styles.tBtn, styles.tBig]} onPress={() => control("playPause")}>
            <Text style={styles.tIconBig}>{np?.status === "Playing" ? "⏸" : "▶"}</Text>
          </Pressable>
          <Pressable style={styles.tBtn} onPress={() => control("next")}>
            <Text style={styles.tIcon}>⏭</Text>
          </Pressable>
        </View>

        <View style={styles.card}>
          <View style={styles.volRow}>
            <Pressable onPress={toggleMute} style={styles.muteBtn}>
              <Text style={styles.muteIcon}>{vol?.mute ? "🔇" : "🔊"}</Text>
            </Pressable>
            <View style={{ flex: 1 }}>
              <Slider
                value={vol?.volume ?? 0}
                minimumValue={0}
                maximumValue={100}
                step={1}
                onSlidingComplete={setVolume}
                minimumTrackTintColor={colors.accent}
                maximumTrackTintColor={colors.border}
                thumbTintColor={colors.accent}
              />
            </View>
            <Text style={styles.volPct}>{vol?.volume ?? 0}%</Text>
          </View>
        </View>
      </View>
    </RemoteScaffold>
  );
}

function sourceName(id?: string): string {
  if (!id) return "";
  // Suele ser "Spotify.exe" o "AppUserModelId!App" — quedarse con lo legible
  const clean = id.split("!")[0].replace(/\.exe$/i, "");
  return clean.length > 30 ? clean.slice(0, 30) + "…" : clean;
}

const styles = StyleSheet.create({
  container:   { flex: 1, padding: spacing.lg, gap: spacing.lg },
  nowPlaying:  { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.xl, borderWidth: 1, borderColor: colors.border, minHeight: 130, justifyContent: "center" },
  title:       { color: colors.text, fontSize: 20, fontWeight: "800" },
  artist:      { color: colors.textDim, fontSize: 14, marginTop: 4 },
  album:       { color: colors.textMuted, fontSize: 12, marginTop: 2 },
  source:      { color: colors.accent, fontSize: 11, fontWeight: "600", marginTop: spacing.md, textTransform: "uppercase", letterSpacing: 1 },
  empty:       { color: colors.textMuted, fontSize: 14, textAlign: "center", fontStyle: "italic" },

  transport:   { flexDirection: "row", justifyContent: "center", alignItems: "center", gap: spacing.lg },
  tBtn:        { width: 64, height: 64, borderRadius: 32, backgroundColor: colors.card, borderWidth: 1, borderColor: colors.border, alignItems: "center", justifyContent: "center" },
  tBig:        { width: 84, height: 84, borderRadius: 42, backgroundColor: colors.accentDim, borderColor: colors.accentDim },
  tIcon:       { color: colors.text, fontSize: 26 },
  tIconBig:    { color: "#fff", fontSize: 34 },

  card:        { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.md, borderWidth: 1, borderColor: colors.border },
  volRow:      { flexDirection: "row", alignItems: "center", gap: spacing.md },
  muteBtn:     { padding: spacing.sm },
  muteIcon:    { fontSize: 22 },
  volPct:      { color: colors.text, fontSize: 14, fontWeight: "700", minWidth: 44, textAlign: "right", fontVariant: ["tabular-nums"] },
});
