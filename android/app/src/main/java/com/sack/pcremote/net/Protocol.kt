package com.sack.pcremote.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ══════════════════════════════════════════════════════════════
// Espejo Kotlin del protocolo WSS del agente. Mismos names/kinds
// para que ambas partes puedan (des)serializar sin transformación.
// Ver docs/PROTOCOL.md en el repo raíz.
// ══════════════════════════════════════════════════════════════

object MsgKinds {
    const val Request       = "request"
    const val Response      = "response"
    const val Subscribe     = "subscribe"
    const val Stream        = "stream"
    const val Unsubscribe   = "unsubscribe"
    const val Ping          = "ping"
    const val Pong          = "pong"
    const val PairInit      = "pair_init"
    const val PairInitAck   = "pair_init_ack"
    const val PairConfirm   = "pair_confirm"
    const val PairResult    = "pair_result"
    const val AuthChallenge = "auth_challenge"
    const val Auth          = "auth"
    const val AuthResult    = "auth_result"
}

@Serializable
data class RequestMsg(
    val kind: String,
    val id: String,
    val domain: String,
    val action: String,
    val params: JsonElement? = null,
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class ResponseMsg(
    val kind: String,
    val id: String,
    val success: Boolean,
    val data: JsonElement? = null,
    val error: ErrorInfo? = null,
    val ts: Long = 0,
)

@Serializable
data class ErrorInfo(
    val code: String,
    val message: String,
    val recoverable: Boolean? = null,
)

@Serializable
data class StreamMsg(
    val kind: String,
    val id: String,
    val data: JsonElement? = null,
    val ts: Long = 0,
)

@Serializable
data class PairInitMsg(val kind: String = MsgKinds.PairInit, val ts: Long = System.currentTimeMillis())

@Serializable
data class PairInitAckMsg(val kind: String, val ttlSec: Int, val ts: Long = 0)

@Serializable
data class PairConfirmMsg(
    val kind: String = MsgKinds.PairConfirm,
    val code: String,
    val deviceName: String,
    val publicKey: String,     // base64
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class PairResultMsg(
    val kind: String,
    val success: Boolean,
    val deviceId: String? = null,
    val agentPublicKey: String? = null,
    val certFingerprint: String? = null,
    val error: ErrorInfo? = null,
    val ts: Long = 0,
)

@Serializable
data class AuthChallengeMsg(val kind: String, val nonce: String, val ts: Long = 0)

@Serializable
data class AuthMsg(
    val kind: String = MsgKinds.Auth,
    val deviceId: String,
    val signature: String,     // base64
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class AuthResultMsg(
    val kind: String,
    val success: Boolean,
    val sessionId: String? = null,
    val error: ErrorInfo? = null,
    val ts: Long = 0,
)

@Serializable
data class SubscribeMsg(
    val kind: String = MsgKinds.Subscribe,
    val id: String,
    val domain: String,
    val action: String,
    val params: JsonElement? = null,
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class UnsubscribeMsg(val kind: String = MsgKinds.Unsubscribe, val id: String, val ts: Long = System.currentTimeMillis())

// ── Domain payloads ─────────────────────────────────────

@Serializable
data class SystemInfo(
    val hostname: String,
    val username: String,
    val os: String,
    val osBuild: String,
    val is64Bit: Boolean,
    val cpuModel: String,
    val cpuCores: Int,
    val ramTotalMB: Long,
    val uptimeSec: Long,
    val timezone: String,
)

@Serializable
data class SystemStats(
    val cpu: Double,
    val ramPct: Double,
    val ramUsedMB: Long,
    val ramTotalMB: Long,
    val ts: Long = 0,
)
