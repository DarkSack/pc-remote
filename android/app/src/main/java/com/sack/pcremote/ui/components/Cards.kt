package com.sack.pcremote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sack.pcremote.ui.theme.NumericStyle

/** Section title with an optional trailing action ("Ver todo"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

/** A value that eases towards its new reading instead of jumping. */
@Composable
fun animatedFraction(value: Float): Float =
    animateFloatAsState(value.coerceIn(0f, 1f), tween(600), label = "fraction").value

/** Circular gauge with the value inside, for the three headline metrics. */
@Composable
fun MetricGauge(
    label: String,
    value: Double?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val fraction = animatedFraction(((value ?: 0.0) / 100.0).toFloat())
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label ${value?.let { "${it.toInt()} por ciento" } ?: "sin datos"}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize(),
                color = color,
                trackColor = color.copy(alpha = 0.16f),
                strokeWidth = 6.dp,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
            Text(pct(value), style = MaterialTheme.typography.titleMedium.merge(NumericStyle))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label, bar and value on one line: "RAM ━━━━━━━──── 58%". */
@Composable
fun MetricBar(
    label: String,
    fraction: Float?,
    valueText: String,
    color: Color,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val animated = animatedFraction(fraction ?: 0f)
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(valueText, style = MaterialTheme.typography.labelLarge.merge(NumericStyle))
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Key   value" row inside cards. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.widthIn(min = 96.dp).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(NumericStyle), modifier = Modifier.weight(1f))
    }
}

/**
 * A metric card: icon, name, the headline value, a few detail lines and a
 * sparkline. Tapping expands it to show the full chart (animateContentSize).
 */
@Composable
fun SystemMetricCard(
    title: String,
    icon: ImageVector,
    color: Color,
    headline: String,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    details: List<Pair<String, String>> = emptyList(),
    history: List<Float> = emptyList(),
    historyMax: Float? = 100f,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    ElevatedCard(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth().animateContentSize()
            .semantics { stateDescription = if (expanded) "Ampliada" else "Contraída" },
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(headline, style = MaterialTheme.typography.titleLarge.merge(NumericStyle))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Contraer" else "Ampliar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp),
                )
            }
            if (fraction != null) {
                Spacer(Modifier.height(10.dp))
                val animated = animatedFraction(fraction)
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = color, trackColor = color.copy(alpha = 0.16f),
                    strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    details.forEach { (k, v) ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, style = MaterialTheme.typography.bodyMedium.merge(NumericStyle), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (history.size >= 2 && !expanded) {
                Spacer(Modifier.height(10.dp))
                Sparkline(history, color, max = historyMax, height = 32.dp)
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    if (history.size >= 2) {
                        MetricChart(history, color, max = historyMax, description = "Historial de $title")
                    }
                    extra?.invoke(this)
                }
            }
        }
    }
}
