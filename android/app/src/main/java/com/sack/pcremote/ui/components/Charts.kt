package com.sack.pcremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// ══════════════════════════════════════════════════════════════
// Gráficas mínimas con Canvas: una línea y un relleno muy tenue.
// Secundarias respecto al número: sin ejes pesados ni leyendas.
// ══════════════════════════════════════════════════════════════

/**
 * Line chart of [values] (oldest first). NaN = no sample (drawn as a gap).
 * [max] fixes the scale (100 for percentages); null scales to the data.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    max: Float? = 100f,
    capacity: Int = values.size,
    fill: Boolean = true,
    description: String = "Gráfica",
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        // Baseline + a dashed mid line: a hint of scale, no more.
        drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        drawLine(grid.copy(alpha = 0.5f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        if (values.size < 2) return@Canvas

        val top = max ?: (values.filter { !it.isNaN() }.maxOrNull()?.coerceAtLeast(1f) ?: 1f) * 1.15f
        val slots = maxOf(capacity, values.size) - 1
        val offset = maxOf(capacity, values.size) - values.size
        fun x(i: Int) = (i + offset).toFloat() / slots * w
        fun y(v: Float) = h - (v / top).coerceIn(0f, 1f) * (h - 2.dp.toPx())

        val line = Path()
        val area = Path()
        var open = false
        var firstX = 0f
        values.forEachIndexed { i, v ->
            if (v.isNaN()) {
                if (open) { area.lineTo(x(i - 1), h); area.lineTo(firstX, h); area.close() }
                open = false
                return@forEachIndexed
            }
            if (!open) {
                line.moveTo(x(i), y(v)); area.moveTo(x(i), h); area.lineTo(x(i), y(v))
                firstX = x(i); open = true
            } else {
                line.lineTo(x(i), y(v)); area.lineTo(x(i), y(v))
            }
        }
        if (open) { area.lineTo(x(values.lastIndex), h); area.lineTo(firstX, h); area.close() }

        if (fill) drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Sparkline with the "10 min … ahora" axis labels under it. */
@Composable
fun HistoryChart(
    values: List<Float>,
    capacity: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    max: Float? = 100f,
    spanLabel: String = "10 min",
    description: String,
) {
    Column(modifier) {
        Sparkline(values, Modifier.fillMaxWidth().height(96.dp), color, max, capacity, description = description)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(spanLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("ahora", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
