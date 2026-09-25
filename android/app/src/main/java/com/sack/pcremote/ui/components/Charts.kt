package com.sack.pcremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ══════════════════════════════════════════════════════════════
// Gráficas con Canvas, sin librerías: una línea suavizada con un relleno
// muy tenue debajo. Son secundarias a los datos: sin ejes pesados, sin
// puntos ni leyendas; el número grande siempre está al lado.
// ══════════════════════════════════════════════════════════════

/** A compact trend line. [max] null = scale to the data (for network speeds). */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    max: Float? = 100f,
    height: Dp = 40.dp,
    capacity: Int = 120,
    description: String? = null,
) {
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { if (description != null) contentDescription = description },
    ) {
        drawTrend(values, color, max, capacity)
    }
}

/**
 * The expanded chart of a metric card: the same line plus faint 0 / 50 / 100 %
 * guides and the time span under it ("2 min" … "ahora").
 */
@Composable
fun MetricChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    max: Float? = 100f,
    capacity: Int = 120,
    spanLabel: String = "2 min",
    description: String? = null,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(96.dp)
                    .semantics { if (description != null) contentDescription = description },
            ) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                for (f in listOf(0f, 0.5f, 1f)) {
                    val y = size.height * f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = if (f == 1f) null else dash)
                }
                drawTrend(values, color, max, capacity)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(spanLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f))
            Text("ahora", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun DrawScope.drawTrend(values: List<Float>, color: Color, max: Float?, capacity: Int) {
    if (values.size < 2) return
    val top = (max ?: values.max()).coerceAtLeast(1f) * if (max == null) 1.15f else 1f
    // Anchored to the right: a short history grows from "now" leftwards.
    val step = size.width / (capacity - 1).coerceAtLeast(1)
    val startX = size.width - step * (values.size - 1)
    fun point(i: Int) = Offset(startX + step * i, size.height * (1f - (values[i] / top).coerceIn(0f, 1f)))

    val line = Path()
    var prev = point(0)
    line.moveTo(prev.x, prev.y)
    for (i in 1 until values.size) {
        val p = point(i)
        val midX = (prev.x + p.x) / 2
        line.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
        prev = p
    }
    val fill = Path().apply {
        addPath(line)
        lineTo(prev.x, size.height)
        lineTo(startX, size.height)
        close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
