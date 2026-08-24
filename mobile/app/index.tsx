import { StyleSheet, Text, View } from "react-native";

// TODO Phase 3: discovery + list of agents.
// Este placeholder muestra que el scaffold arranca.

export default function DiscoveryScreen() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>PC Remote</Text>
      <Text style={styles.subtitle}>Descubriendo PCs en tu red…</Text>
      <Text style={styles.hint}>
        (Scaffolding fase 0 — implementación en fase 3)
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
    backgroundColor: "#0f172a",
  },
  title: {
    color: "#e6edf3",
    fontSize: 32,
    fontWeight: "800",
    marginBottom: 8,
  },
  subtitle: {
    color: "#8b95a5",
    fontSize: 16,
    marginBottom: 24,
  },
  hint: {
    color: "#4a556b",
    fontSize: 13,
    fontStyle: "italic",
  },
});
