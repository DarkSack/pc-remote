package com.sack.pcremote.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ══════════════════════════════════════════════════════════════
// Persistencia cifrada de credenciales de dispositivos emparejados.
// Backing store: EncryptedSharedPreferences con clave maestra en
// Android Keystore (AES-256 GCM).
// ══════════════════════════════════════════════════════════════

@Serializable
data class AgentCredentials(
    val deviceId: String,
    val privateSeedB64: String,
    val publicKeyB64: String,
    val agentHost: String,
    val agentPort: Int,
    val agentName: String,
    val certFingerprintHex: String,
)

class CredentialsStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "pcremote_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(c: AgentCredentials) {
        prefs.edit()
            .putString(keyFor(c.deviceId), json.encodeToString(c))
            .apply()
        val ids = listIds().toMutableSet().apply { add(c.deviceId) }
        prefs.edit().putStringSet(INDEX_KEY, ids).apply()
    }

    fun load(deviceId: String): AgentCredentials? {
        val raw = prefs.getString(keyFor(deviceId), null) ?: return null
        return runCatching { json.decodeFromString<AgentCredentials>(raw) }.getOrNull()
    }

    fun listAll(): List<AgentCredentials> =
        listIds().mapNotNull { load(it) }

    fun delete(deviceId: String) {
        prefs.edit().remove(keyFor(deviceId)).apply()
        val ids = listIds().toMutableSet().apply { remove(deviceId) }
        prefs.edit().putStringSet(INDEX_KEY, ids).apply()
    }

    private fun listIds(): Set<String> =
        prefs.getStringSet(INDEX_KEY, emptySet()) ?: emptySet()

    companion object {
        private const val INDEX_KEY = "__index"
        private fun keyFor(id: String) = "device_$id"
    }
}
