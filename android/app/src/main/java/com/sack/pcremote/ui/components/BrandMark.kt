package com.sack.pcremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The PC Remote mark (assets/brand/mark.svg): a device outline with an open port,
 * and a link leaving its core towards a remote node. Two-tone: the outline in
 * the content colour, the core and link in the brand colour.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    line: Color = MaterialTheme.colorScheme.onSurface,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(size).semantics { contentDescription = "PC Remote" }) {
        scale(this.size.minDimension / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round)
            val outline = Path().apply {
                moveTo(15f, 9f); lineTo(15f, 7.5f)
                arcTo(Rect(8f, 4f, 15f, 11f), 0f, -90f, false)
                lineTo(6.5f, 4f)
                arcTo(Rect(3f, 4f, 10f, 11f), -90f, -90f, false)
                lineTo(3f, 16.5f)
                arcTo(Rect(3f, 13f, 10f, 20f), 180f, -90f, false)
                lineTo(11.5f, 20f)
                arcTo(Rect(8f, 13f, 15f, 20f), 90f, -90f, false)
                lineTo(15f, 15f)
            }
            drawPath(outline, line, style = stroke)
            drawCircle(accent, radius = 2.5f, center = Offset(9f, 12f))
            drawLine(accent, Offset(11.5f, 12f), Offset(18f, 12f), strokeWidth = 2f, cap = StrokeCap.Round)
            drawCircle(accent, radius = 2.25f, center = Offset(20f, 12f))
        }
    }
}

/** Mark + name, for top bars and the devices screen. */
@Composable
fun BrandLockup(subtitle: String? = "Command Center") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("PC Remote", style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
