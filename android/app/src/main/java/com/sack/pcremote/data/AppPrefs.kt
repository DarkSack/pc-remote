package com.sack.pcremote.data

import android.content.Context
import org.json.JSONArray

// ══════════════════════════════════════════════════════════════
// Favoritas y recientes del lanzador, por PC.
//
// Se identifican los PCs por la huella de su certificado (no por IP ni
// nombre, que cambian) y las apps por el id que da el agente. No hay nada
// secreto aquí, así que van en SharedPreferences normales.
// ══════════════════════════════════════════════════════════════

class AppPrefs(context: Context, pcFingerprint: String) {

    private val prefs = context.getSharedPreferences("apps_${pcFingerprint.take(16).lowercase()}", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(appId: String): Set<String> {
        val next = favorites().toMutableSet().apply { if (!add(appId)) remove(appId) }
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
        return next
    }

    /** Most recent first. Ordered, so it is stored as a JSON array rather than a set. */
    fun recents(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_RECENTS, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun addRecent(appId: String): List<String> {
        val next = (listOf(appId) + recents().filter { it != appId }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, JSONArray(next).toString()).apply()
        return next
    }

    /** Forget ids the PC no longer has (app uninstalled), so empty rows never linger. */
    fun prune(existing: Set<String>) {
        val fav = favorites()
        val rec = recents()
        if (fav.all { it in existing } && rec.all { it in existing }) return
        prefs.edit()
            .putStringSet(KEY_FAVORITES, fav.filter { it in existing }.toSet())
            .putString(KEY_RECENTS, JSONArray(rec.filter { it in existing }).toString())
            .apply()
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val MAX_RECENTS = 8
    }
}
