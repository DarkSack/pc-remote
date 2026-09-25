package com.sack.pcremote.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// Small, locale-aware formatting helpers shared by every screen.

fun formatBytes(bytes: Long): String {
    val b = abs(bytes).toDouble()
    return when {
        b >= 1L shl 40 -> "%.1f TB".format(bytes / (1L shl 40).toDouble())
        b >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}

fun formatMB(mb: Long): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

/** Network speed in bits per second, the way connections are advertised (Mbps). */
fun formatBitrate(bytesPerSecond: Long): String {
    val bits = bytesPerSecond * 8.0
    return when {
        bits >= 1e9 -> "%.1f Gbps".format(bits / 1e9)
        bits >= 1e6 -> "%.1f Mbps".format(bits / 1e6)
        bits >= 1e3 -> "%.0f kbps".format(bits / 1e3)
        else -> "%.0f bps".format(bits)
    }
}

fun formatDuration(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "$d d $h h"
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        else -> "${seconds.coerceAtLeast(0)} s"
    }
}

/** "hace 5 s", "hace 3 min", "hace 2 h", or a date for older things. */
fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - epochMs) / 1000
    return when {
        s < 5 -> "ahora"
        s < 60 -> "hace $s s"
        s < 3600 -> "hace ${s / 60} min"
        s < 86_400 -> "hace ${s / 3600} h"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}

fun clockTime(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

fun dayLabel(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val day = fmt.format(Date(epochMs))
    return when (day) {
        fmt.format(Date(now)) -> "Hoy"
        fmt.format(Date(now - 86_400_000)) -> "Ayer"
        else -> SimpleDateFormat("EEEE d 'de' MMMM", Locale.getDefault()).format(Date(epochMs))
            .replaceFirstChar { it.uppercase() }
    }
}

fun pct(value: Double?): String = value?.let { "${it.toInt()}%" } ?: "—"
