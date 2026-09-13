package com.sack.pcremote.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ══════════════════════════════════════════════════════════════
// Persistencia cifrada de credenciales de dispositivos emparejados.
//
// Cada entrada es el JSON de AgentCredentials cifrado con AES-256-GCM.
// La clave vive en Android Keystore y nunca sale de él; en SharedPreferences
// solo queda iv + texto cifrado en base64.
//
// Antes esto usaba EncryptedSharedPreferences (androidx.security-crypto),
// que está deprecado. Las credenciales guardadas así se migran solas la
// primera vez que se abre la app (ver migrateLegacy).
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

class CredentialsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    fun save(c: AgentCredentials) {
        val ids = listIds().toMutableSet().apply { add(c.deviceId) }
        prefs.edit()
            .putString(keyFor(c.deviceId), encrypt(json.encodeToString(AgentCredentials.serializer(), c)))
            .putStringSet(INDEX_KEY, ids)
            .apply()
    }

    fun load(deviceId: String): AgentCredentials? {
        val blob = prefs.getString(keyFor(deviceId), null) ?: return null
        return runCatching {
            json.decodeFromString(AgentCredentials.serializer(), decrypt(blob))
        }.onFailure { Log.w(TAG, "Could not read credentials for $deviceId", it) }.getOrNull()
    }

    fun listAll(): List<AgentCredentials> =
        listIds().mapNotNull { load(it) }

    fun delete(deviceId: String) {
        val ids = listIds().toMutableSet().apply { remove(deviceId) }
        prefs.edit()
            .remove(keyFor(deviceId))
            .putStringSet(INDEX_KEY, ids)
            .apply()
    }

    private fun listIds(): Set<String> =
        prefs.getStringSet(INDEX_KEY, emptySet())?.toSet() ?: emptySet()

    // ── AES-GCM with a Keystore key ────────────────────────────

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val ivLen = raw[0].toInt()
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen), Charsets.UTF_8)
    }

    // ── Migration from EncryptedSharedPreferences (<= 0.1.0) ───

    /**
     * Copies credentials saved by the old store, then deletes the old file and its
     * master key. If anything fails the old file is left untouched, so a later
     * version can still retry; the user would only have to pair again.
     */
    @Suppress("DEPRECATION")
    private fun migrateLegacy() {
        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")
        if (!legacyFile.exists()) return

        runCatching {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val legacy = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                LEGACY_PREFS_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val ids = legacy.getStringSet(INDEX_KEY, emptySet()) ?: emptySet()
            var moved = 0
            for (id in ids) {
                val raw = legacy.getString(keyFor(id), null) ?: continue
                val creds = runCatching { json.decodeFromString(AgentCredentials.serializer(), raw) }.getOrNull() ?: continue
                save(creds)
                moved++
            }
            // commit(): the old data must only go away once the new data is on disk.
            check(prefs.edit().putStringSet(INDEX_KEY, listIds()).commit()) { "could not persist migrated credentials" }

            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    .deleteEntry(androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            Log.i(TAG, "Migrated $moved credential(s) from EncryptedSharedPreferences")
        }.onFailure { Log.w(TAG, "Legacy credentials migration failed; old store kept", it) }
    }

    companion object {
        private const val TAG = "CredentialsStore"
        private const val PREFS_NAME = "pcremote_creds_v2"
        private const val LEGACY_PREFS_NAME = "pcremote_creds"
        private const val INDEX_KEY = "__index"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pcremote_credentials_v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private fun keyFor(id: String) = "device_$id"
    }
}
