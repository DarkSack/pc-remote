import { useMemo } from "react";
import { PanResponder, Pressable, StyleSheet, Text, View } from "react-native";
import { RemoteScaffold } from "@/ui/RemoteScaffold";
import { useConnection } from "@/stores/connection";
import { ConnectionManager } from "@/net/connection";
import { colors, radius, spacing } from "@/ui/theme";

// ══════════════════════════════════════════════════════════════
// Touchpad: gestos → input.mouseMove / mouseClick / mouseScroll.
//
// - 1 dedo arrastrando → mouseMove (throttle 16 ms ≈ 60 Hz).
// - Tap (< 300 ms, < 8 px de recorrido) → click izquierdo.
// - Long-press (> 550 ms sin recorrer > 8 px) → click derecho.
// - 2 dedos vertical → scroll (invertido: arrastrar abajo = scroll abajo).
// - Botones abajo para click izq/medio/der y doble click.
// ══════════════════════════════════════════════════════════════

const EMIT_INTERVAL_MS   = 16;
const SCROLL_SENSITIVITY = 0.02;
const TAP_MAX_DISTANCE   = 8;
const TAP_MAX_DURATION   = 300;
const LONG_PRESS_MS      = 550;

export default function TouchpadScreen() {
  const manager = useConnection((s) => s.manager);

  const panHandlers = useMemo(() => makePanResponder(manager).panHandlers, [manager]);

  function click(button: "left" | "right" | "middle", count = 1) {
    manager?.request("input", "mouseClick", { button, count }).catch(() => {});
  }

  return (
    <RemoteScaffold title="Touchpad">
      <View style={styles.container}>
        <View style={styles.pad} {...panHandlers}>
          <Text style={styles.hint}>
            Arrastra: mover · Tap: click{"\n"}2 dedos vertical: scroll · Long press: click derecho
          </Text>
        </View>
        <View style={styles.buttons}>
          <Pressable style={[styles.btn, styles.btnPrimary]} onPress={() => click("left")}>
            <Text style={styles.btnText}>Click izq.</Text>
          </Pressable>
          <Pressable style={styles.btn} onPress={() => click("middle")}>
            <Text style={styles.btnText}>Medio</Text>
          </Pressable>
          <Pressable style={styles.btn} onPress={() => click("right")}>
            <Text style={styles.btnText}>Click der.</Text>
          </Pressable>
          <Pressable style={styles.btn} onPress={() => click("left", 2)}>
            <Text style={styles.btnText}>Doble click</Text>
          </Pressable>
        </View>
      </View>
    </RemoteScaffold>
  );
}

// ── Pan handler (fuera del componente para capturar el manager) ──
function makePanResponder(manager: ConnectionManager | null) {
  const s = {
    lastX: 0, lastY: 0,
    started: 0,
    totalDist: 0,
    accum: { dx: 0, dy: 0 },
    lastEmit: 0,
    longPressTimer: null as ReturnType<typeof setTimeout> | null,
    longPressed: false,
    twoFinger: null as { y: number; accum: number } | null,
  };

  function emit() {
    const now = Date.now();
    if (now - s.lastEmit < EMIT_INTERVAL_MS) return;
    const { dx, dy } = s.accum;
    if (dx === 0 && dy === 0) return;
    s.accum = { dx: 0, dy: 0 };
    s.lastEmit = now;
    manager?.request("input", "mouseMove", { dx, dy }).catch(() => {});
  }

  return PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder:  () => true,

    onPanResponderGrant: (e) => {
      const t = e.nativeEvent.touches[0];
      s.lastX = t.pageX; s.lastY = t.pageY;
      s.started = Date.now();
      s.totalDist = 0;
      s.longPressed = false;
      s.twoFinger = null;
      s.longPressTimer = setTimeout(() => {
        s.longPressed = true;
        manager?.request("input", "mouseClick", { button: "right" }).catch(() => {});
      }, LONG_PRESS_MS);
    },

    onPanResponderMove: (e) => {
      const touches = e.nativeEvent.touches;
      if (touches.length >= 2) {
        if (s.longPressTimer) { clearTimeout(s.longPressTimer); s.longPressTimer = null; }
        const y = touches[0].pageY;
        if (!s.twoFinger) s.twoFinger = { y, accum: 0 };
        const dy = y - s.twoFinger.y;
        s.twoFinger.y = y;
        s.twoFinger.accum += dy * SCROLL_SENSITIVITY;
        const step = Math.trunc(s.twoFinger.accum);
        if (step !== 0) {
          s.twoFinger.accum -= step;
          // -step: arrastrar hacia abajo debe scrollear el contenido hacia abajo
          manager?.request("input", "mouseScroll", { amount: -step }).catch(() => {});
        }
        return;
      }

      const t = touches[0];
      const dx = t.pageX - s.lastX;
      const dy = t.pageY - s.lastY;
      s.lastX = t.pageX; s.lastY = t.pageY;
      s.totalDist += Math.abs(dx) + Math.abs(dy);
      if (s.totalDist > TAP_MAX_DISTANCE && s.longPressTimer) {
        clearTimeout(s.longPressTimer); s.longPressTimer = null;
      }
      s.accum.dx += dx;
      s.accum.dy += dy;
      emit();
    },

    onPanResponderRelease: () => {
      if (s.longPressTimer) { clearTimeout(s.longPressTimer); s.longPressTimer = null; }
      emit();
      const dur = Date.now() - s.started;
      if (!s.longPressed && !s.twoFinger && s.totalDist < TAP_MAX_DISTANCE && dur < TAP_MAX_DURATION) {
        manager?.request("input", "mouseClick", { button: "left" }).catch(() => {});
      }
    },

    onPanResponderTerminate: () => {
      if (s.longPressTimer) { clearTimeout(s.longPressTimer); s.longPressTimer = null; }
    },
  });
}

const styles = StyleSheet.create({
  container:  { flex: 1, padding: spacing.md, gap: spacing.md },
  pad:        { flex: 1, backgroundColor: colors.card, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, alignItems: "center", justifyContent: "center", padding: spacing.lg },
  hint:       { color: colors.textMuted, fontSize: 12, textAlign: "center", lineHeight: 18 },
  buttons:    { flexDirection: "row", gap: spacing.sm, flexWrap: "wrap" },
  btn:        { flex: 1, minWidth: 90, backgroundColor: colors.bgAlt, borderWidth: 1, borderColor: colors.border, paddingVertical: spacing.md, borderRadius: radius.md, alignItems: "center" },
  btnPrimary: { backgroundColor: colors.accentDim, borderColor: colors.accentDim },
  btnText:    { color: colors.text, fontSize: 13, fontWeight: "700" },
});
