package com.sack.pcremote.ui.components

import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Formatting shared by every screen. Spanish-style decimals ("1,8 GB").

private val es = Locale.forLanguageTag("es")

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(es, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(es, "%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format(es, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
}

fun formatMB(mb: Long): String = if (mb >= 1024) String.format(es, "%.1f GB", mb / 1024.0) else "$mb MB"

fun formatGB(gb: Double): String = if (gb >= 1000) String.format(es, "%.1f TB", gb / 1024) else String.format(es, "%.0f GB", gb)

/** Network rate from bytes/s, in bits like the ISP quotes it. */
fun formatRate(bytesPerSec: Long): String {
    val bits = bytesPerSec * 8.0
    return when {
        bits < 1_000 -> "${bits.toInt()} b/s"
        bits < 1_000_000 -> String.format(es, "%.0f Kb/s", bits / 1_000)
        bits < 1_000_000_000 -> String.format(es, "%.1f Mb/s", bits / 1_000_000)
        else -> String.format(es, "%.2f Gb/s", bits / 1_000_000_000)
    }
}

fun formatPct(v: Double?): String = v?.let { "${it.toInt()} %" } ?: "—"

fun formatDuration(sec: Long): String {
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
    return when {
        d > 0 -> "${d} d ${h} h"
        h > 0 -> "${h} h ${m} min"
        m > 0 -> "$m min"
        else -> "${sec} s"
    }
}

fun formatClock(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, es).format(Date(ts))

fun formatDate(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, es).format(Date(ts))

/** "hace 5 s", "hace 3 min", or the time. */
fun formatAgo(ts: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "ahora"
        s < 60 -> "hace $s s"
        s < 3600 -> "hace ${s / 60} min"
        s < 86400 -> "hace ${s / 3600} h"
        else -> formatDate(ts)
    }
}
