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
    // Wake-on-LAN data; null on agents older than 0.3.
    val macAddress: String? = null,
    val broadcast: String? = null,
    val lanIp: String? = null,
    val hostname: String,
    val username: String,
    val os: String,
    val osBuild: String,
    val is64Bit: Boolean,
    val cpuModel: String,
    val cpuCores: Int,
    val gpuModel: String? = null,
    val ramTotalMB: Long,
    val uptimeSec: Long,
    val timezone: String,
)

@Serializable
data class NowPlaying(
    val active: Boolean = false,
    val source: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val status: String? = null,
    val volume: Int? = null,
    val mute: Boolean? = null,
    val trackChanged: Boolean = false,
    val artworkBase64: String? = null,
)

@Serializable
data class AppEntry(val id: String, val name: String, val source: String)

@Serializable
data class AppList(val version: Long = 0, val count: Int = 0, val applications: List<AppEntry> = emptyList())

@Serializable
data class AppIcons(val icons: Map<String, String?> = emptyMap())

@Serializable
data class ClipboardText(val text: String = "", val length: Int = 0)

@Serializable
data class GpuStats(
    val name: String? = null,
    val usage: Double? = null,
    val tempC: Double? = null,
    val vramUsedMB: Long? = null,
    val vramTotalMB: Long? = null,
    val clockMHz: Int? = null,
)

@Serializable
data class DiskStats(val name: String, val label: String? = null, val totalGB: Double = 0.0, val freeGB: Double = 0.0, val usedPct: Double = 0.0)

@Serializable
data class NetStats(val downBps: Long = 0, val upBps: Long = 0)

@Serializable
data class SystemStats(
    val cpu: Double,
    val cpuFreqMHz: Double? = null,
    val cpuTempC: Double? = null,
    val ramPct: Double,
    val ramUsedMB: Long,
    val ramTotalMB: Long,
    // Agents before 0.4 send only cpu + ram.
    val gpu: GpuStats? = null,
    val disks: List<DiskStats> = emptyList(),
    val net: NetStats? = null,
    val uptimeSec: Long? = null,
    val ts: Long = 0,
)

// ── Activity ────────────────────────────────────────────
@Serializable
data class ActivityEvent(
    val id: Long,
    val ts: Long,
    val type: String,
    val title: String,
    val detail: String? = null,
    val severity: String = "info",
)

// ── Plugins ─────────────────────────────────────────────
@Serializable
data class Feature(val id: String, val name: String, val description: String = "", val icon: String? = null, val enabled: Boolean = false)

@Serializable
data class PluginParam(
    val id: String,
    val label: String = id,
    val type: String = "string",
    val required: Boolean = true,
    val default: JsonElement? = null,
    val options: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val placeholder: String? = null,
)

@Serializable
data class PluginAction(
    val id: String,
    val label: String = id,
    val description: String? = null,
    val icon: String? = null,
    val confirm: String? = null,
    val output: Boolean = false,
    val timeoutSec: Int = 0,
    val params: List<PluginParam> = emptyList(),
)

@Serializable
data class Plugin(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val version: String? = null,
    val author: String? = null,
    val kind: String = "actions",
    val enabled: Boolean = false,
    val loaded: Boolean = true,
    val errors: List<String> = emptyList(),
    val actions: List<PluginAction> = emptyList(),
)

@Serializable
data class PluginCatalog(val folder: String? = null, val features: List<Feature> = emptyList(), val plugins: List<Plugin> = emptyList())

@Serializable
data class PluginRunResult(
    val started: Boolean = true,
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

// ── Processes ───────────────────────────────────────────
@Serializable
data class ProcessGroup(
    val name: String,
    val count: Int = 1,
    val pids: List<Int> = emptyList(),
    val cpu: Double = 0.0,
    val workingMB: Long = 0,
    val title: String? = null,
    val isProtected: Boolean = false,
)

@Serializable
data class ProcessList(val count: Int = 0, val total: Int = 0, val processes: List<ProcessGroup> = emptyList())

// ── Windows (for the apps' running state) ───────────────
@Serializable
data class WindowInfo(
    val hwnd: Long,
    val title: String = "",
    val pid: Long = 0,
    val process: String = "",
    val description: String? = null,
    val foreground: Boolean = false,
    val minimized: Boolean = false,
)

@Serializable
data class WindowList(val windows: List<WindowInfo> = emptyList())

// ── Network ─────────────────────────────────────────────
@Serializable
data class NetInterface(
    val name: String,
    val description: String? = null,
    val type: String = "other",
    val up: Boolean = false,
    val ipv4: String? = null,
    val prefixLength: Int? = null,
    val ipv6: String? = null,
    val mac: String? = null,
    val speedMbps: Long? = null,
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
    val primary: Boolean = false,
)

@Serializable
data class TcpSummary(val established: Int = 0, val listening: Int = 0, val timeWait: Int = 0, val total: Int = 0)

@Serializable
data class RemoteEndpoint(val address: String, val ports: List<Int> = emptyList(), val count: Int = 0, val local: Boolean = false)

@Serializable
data class NetworkInfo(
    val hostname: String = "",
    val lanIp: String? = null,
    val mac: String? = null,
    val interfaces: List<NetInterface> = emptyList(),
    val tcp: TcpSummary = TcpSummary(),
    val remote: List<RemoteEndpoint> = emptyList(),
)

@Serializable
data class PingResult(val host: String, val sent: Int = 0, val lost: Int = 0, val avgMs: Double? = null, val minMs: Long? = null, val maxMs: Long? = null)

// ── Files ───────────────────────────────────────────────
@Serializable
data class Place(val name: String, val icon: String? = null, val path: String)

@Serializable
data class Drive(
    val name: String,
    val path: String,
    val label: String? = null,
    val type: String = "fixed",
    val ready: Boolean = true,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
)

@Serializable
data class FileRoots(val places: List<Place> = emptyList(), val drives: List<Drive> = emptyList())

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val dir: Boolean = false,
    val size: Long? = null,
    val modified: Long = 0,
    val ext: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class FolderListing(
    val path: String,
    val name: String = "",
    val parent: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class FileChunk(val name: String = "", val offset: Long = 0, val length: Int = 0, val total: Long = 0, val eof: Boolean = true, val data: String = "")

// ── Clipboard history ───────────────────────────────────
@Serializable
data class ClipItem(
    val id: String,
    val ts: Long = 0,
    val kind: String = "text",
    val preview: String? = null,
    val length: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    val thumbnail: String? = null,
    val files: List<String>? = null,
)

@Serializable
data class ClipFull(val id: String, val kind: String = "text", val text: String? = null, val width: Int = 0, val height: Int = 0, val png: String? = null)

// ── Terminal ────────────────────────────────────────────
@Serializable
data class TerminalInfo(val shell: String = "PowerShell", val cwd: String = "", val user: String = "", val host: String = "")
