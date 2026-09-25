package com.sack.pcremote.ui.pc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sack.pcremote.session.PcSession
import com.sack.pcremote.ui.components.*
import java.util.Locale

// ══════════════════════════════════════════════════════════════
// Monitor: los últimos 10 minutos de CPU, RAM, GPU y red, y el
// detalle del hardware (frecuencias, temperaturas, VRAM, discos).
// ══════════════════════════════════════════════════════════════

@Composable
fun MonitorScreen(vm: PcSession, back: () -> Unit) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val version by vm.history.version.collectAsStateWithLifecycle() // recompose on every sample
    val h = vm.history

    SubScreen("Monitor", back, subtitle = vm.displayName()) { padding ->
        val s = stats
        if (s == null) {
            Box(Modifier.padding(padding)) { RequireConnection(vm) { SkeletonList(5) } }
            return@SubScreen
        }
        // `version` is read so these lists are rebuilt on each new sample.
        val cpu = remember(version) { h.cpu.toList() }
        val ram = remember(version) { h.ram.toList() }
        val gpu = remember(version) { h.gpu.toList() }
        val down = remember(version) { h.down.toList() }
        val up = remember(version) { h.up.toList() }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ChartCard(Icons.Outlined.Memory, "CPU", formatPct(s.cpu), cpu, h.capacity, MaterialTheme.colorScheme.primary) {
                    info?.let { Detail("Modelo", it.cpuModel) ; Detail("Núcleos lógicos", "${it.cpuCores}") }
                    s.cpuFreqMHz?.let { Detail("Frecuencia", String.format(Locale.ROOT, "%.2f GHz", it / 1000)) }
                    Detail("Temperatura", s.cpuTempC?.let { "${it.toInt()} °C" } ?: "No disponible en este PC")
                    Detail("Media 10 min", cpu.average().takeIf { !it.isNaN() }?.let { "${it.toInt()} %" } ?: "—")
                }
            }
            item {
                ChartCard(Icons.Outlined.Storage, "Memoria", formatPct(s.ramPct), ram, h.capacity, MaterialTheme.colorScheme.secondary) {
                    Detail("En uso", formatMB(s.ramUsedMB))
                    Detail("Libre", formatMB(s.ramTotalMB - s.ramUsedMB))
                    Detail("Total", formatMB(s.ramTotalMB))
                }
            }
            s.gpu?.let { g ->
                item {
                    ChartCard(Icons.Outlined.VideogameAsset, "GPU", formatPct(g.usage), gpu, h.capacity, MaterialTheme.colorScheme.tertiary) {
                        Detail("Modelo", g.name ?: info?.gpuModel ?: "—")
                        g.tempC?.let { Detail("Temperatura", "${it.toInt()} °C") }
                        g.clockMHz?.let { Detail("Frecuencia", "$it MHz") }
                        g.vramUsedMB?.let { u -> Detail("VRAM", formatMB(u) + (g.vramTotalMB?.let { " de ${formatMB(it)}" } ?: "")) }
                        if (g.vramUsedMB != null && g.vramTotalMB != null && g.vramTotalMB > 0) {
                            Spacer(Modifier.height(4.dp))
                            MetricBar("", "", g.vramUsedMB.toFloat() / g.vramTotalMB, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
            item {
                val net = s.net
                ChartCard(Icons.Outlined.ArrowDownward, "Red · bajada", net?.let { formatRate(it.downBps) } ?: "—", down, h.capacity,
                    MaterialTheme.colorScheme.secondary, max = null) {
                    Text("Subida", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Sparkline(up, Modifier.fillMaxWidth().height(48.dp), MaterialTheme.colorScheme.primary, null, h.capacity, description = "Historial de subida")
                    Detail("Subida ahora", net?.let { formatRate(it.upBps) } ?: "—")
                    Detail("Pico de bajada", down.filter { !it.isNaN() }.maxOrNull()?.let { formatRate(it.toLong()) } ?: "—")
                }
            }
            if (s.disks.isNotEmpty()) {
                item { SectionHeader("Almacenamiento") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            s.disks.forEach { d ->
                                MetricBar(
                                    listOfNotNull(d.name, d.label?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                    formatPct(d.usedPct),
                                    (d.usedPct / 100).toFloat(),
                                    icon = Icons.Outlined.SdStorage,
                                    detail = "${formatGB(d.freeGB)} libres de ${formatGB(d.totalGB)}",
                                )
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Sistema") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        info?.let {
                            Detail("Equipo", it.hostname)
                            Detail("Sistema", "${it.os} (${it.osBuild})")
                            Detail("Arquitectura", if (it.is64Bit) "64 bits" else "32 bits")
                            Detail("Zona horaria", it.timezone)
                        }
                        (s.uptimeSec ?: info?.uptimeSec)?.let { Detail("Encendido desde hace", formatDuration(it)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    icon: ImageVector, title: String, value: String, values: List<Float>, capacity: Int, color: Color,
    max: Float? = 100f,
    details: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            HistoryChart(values, capacity, color = color, max = max, description = "Historial de $title")
            Spacer(Modifier.height(12.dp))
            details()
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}
