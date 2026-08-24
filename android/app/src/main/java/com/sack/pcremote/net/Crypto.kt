package com.sack.pcremote.net

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

// ══════════════════════════════════════════════════════════════
// Ed25519 vía BouncyCastle — no depende de la JCA moderna (Java 15+)
// y funciona en cualquier Android >= 26.
// ══════════════════════════════════════════════════════════════

object Crypto {
    private val rng = SecureRandom()

    data class Keypair(val privateSeed: ByteArray, val publicKey: ByteArray)

    /** Genera un keypair Ed25519. La semilla privada (32 bytes) es toda la key. */
    fun generateKeypair(): Keypair {
        val seed = ByteArray(32).also(rng::nextBytes)
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub  = priv.generatePublicKey().encoded
        return Keypair(seed, pub)
    }

    /** Firma bytes con la semilla privada. Devuelve la firma cruda (64 bytes). */
    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(privateSeed, 0)
        return Ed25519Signer().apply {
            init(true, priv)
            update(message, 0, message.size)
        }.generateSignature()
    }

    // ── Codificaciones ─────────────────────────────────
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun fromB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun fromHex(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
