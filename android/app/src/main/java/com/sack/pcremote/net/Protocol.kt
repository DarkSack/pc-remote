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
// Every field the agent may omit (older agents, values Windows does not
// expose) has a default, so a missing key never breaks decoding.

@Serializable
data class SystemInfo(
    // Wake-on-LAN data; null on agents older than 0.3.
    val macAddress: String? = null,
    val broadcast: String? = null,
    val lanIp: String? = null,
    val hostname: String = "",
    val username: String = "",
    val os: String = "Windows",
    val osBuild: String = "",
    val is64Bit: Boolean = true,
    val cpuModel: String = "",
    val cpuCores: Int = 0,
    val ramTotalMB: Long = 0,
    val gpuName: String? = null,
    val vramTotalMB: Long? = null,
    val uptimeSec: Long = 0,
    val timezone: String = "",
    val agentVersion: String? = null,
)

@Serializable
data class SystemStats(
    val cpu: Double = 0.0,
    val cpuFreqMHz: Int? = null,
    val cpuTempC: Double? = null,
    val ramPct: Double = 0.0,
    val ramUsedMB: Long = 0,
    val ramTotalMB: Long = 0,
    val gpu: GpuStats? = null,
    val disks: List<DiskStats> = emptyList(),
    val net: NetStats? = null,
    val uptimeSec: Long? = null,
    val ts: Long = 0,
)

@Serializable
data class GpuStats(
    val name: String? = null,
    val usage: Double? = null,
    val vramUsedMB: Long? = null,
    val vramTotalMB: Long? = null,
    val tempC: Double? = null,
)

@Serializable
data class DiskStats(
    val name: String,
    val label: String? = null,
    val totalGB: Double = 0.0,
    val freeGB: Double = 0.0,
    val usedPct: Double = 0.0,
)

@Serializable
data class NetStats(
    val rxBps: Long = 0,
    val txBps: Long = 0,
    val iface: String? = null,
    val linkMbps: Long? = null,
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

// ── Plugins ──────────────────────────────────────────────

@Serializable
data class PluginInfo(
    val id: String,
    val domain: String? = null,
    val name: String,
    val description: String = "",
    val category: String = "other",
    val version: String = "",
    val builtIn: Boolean = true,
    val enabled: Boolean = true,
    val canDisable: Boolean = true,
    val sensitive: Boolean = false,
    val loaded: Boolean = true,
    val restartRequired: Boolean = false,
    val actions: List<String> = emptyList(),
)

@Serializable
data class PluginList(val plugins: List<PluginInfo> = emptyList())

// ── Activity ─────────────────────────────────────────────

@Serializable
data class ActivityEvent(
    val ts: Long,
    val kind: String,
    val title: String,
    val detail: String? = null,
    val level: String = "info",
    val device: String? = null,
)

@Serializable
data class ActivityList(val events: List<ActivityEvent> = emptyList())

// ── Clipboard (live + history) ───────────────────────────

@Serializable
data class ClipboardState(
    val type: String = "text",
    val text: String = "",
    val length: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val files: List<String>? = null,
    val fileCount: Int = 0,
    val thumbBase64: String? = null,
    val isPrivate: Boolean = false,
    val historyVersion: Long = 0,
)

@Serializable
data class ClipHistoryItem(
    val id: Long,
    val type: String,
    val ts: Long,
    val preview: String? = null,
    val length: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val files: List<String>? = null,
    val fileCount: Int = 0,
    val thumbBase64: String? = null,
)

@Serializable
data class ClipHistoryPage(val version: Long = 0, val total: Int = 0, val items: List<ClipHistoryItem> = emptyList())

@Serializable
data class ClipFull(
    val id: Long,
    val type: String,
    val text: String? = null,
    val length: Int = 0,
    val pngBase64: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val files: List<String>? = null,
)

// ── Processes / windows ──────────────────────────────────

@Serializable
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val workingMB: Long = 0,
    val cpu: Double? = null,
    val threads: Int = 0,
    val startTime: String? = null,
    val windowTitle: String? = null,
)

@Serializable
data class ProcessList(val count: Int = 0, val total: Int = 0, val processes: List<ProcessInfo> = emptyList())

@Serializable
data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val pid: Int = 0,
    val process: String? = null,
    val foreground: Boolean = false,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
)

@Serializable
data class WindowList(val windows: List<WindowInfo> = emptyList())

// ── Terminal ─────────────────────────────────────────────

@Serializable
data class TerminalInfo(
    val cwd: String = "",
    val shell: String = "powershell",
    val shells: List<String> = listOf("powershell", "cmd"),
    val user: String = "",
    val host: String = "",
)

@Serializable
data class TerminalResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val cwd: String = "",
    val durationMs: Long = 0,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

// ── Files ────────────────────────────────────────────────

@Serializable
data class FileRoot(
    val name: String,
    val path: String,
    val kind: String,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
)

@Serializable
data class FileRoots(val folders: List<FileRoot> = emptyList(), val drives: List<FileRoot> = emptyList())

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long? = null,
    val modified: Long = 0,
    val ext: String? = null,
)

@Serializable
data class FileListing(
    val path: String,
    val parent: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class FileContent(val name: String, val size: Long, val base64: String)

// ── Network ──────────────────────────────────────────────

@Serializable
data class NetInterface(
    val name: String,
    val description: String = "",
    val type: String = "other",
    val up: Boolean = false,
    val speedMbps: Long? = null,
    val mac: String? = null,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val gateways: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val primary: Boolean = false,
)

@Serializable
data class TcpSummary(val total: Int = 0, val established: Int = 0, val listeners: Int = 0)

@Serializable
data class NetworkInfo(
    val hostname: String = "",
    val lanIp: String? = null,
    val interfaces: List<NetInterface> = emptyList(),
    val tcp: TcpSummary = TcpSummary(),
)

@Serializable
data class TcpConnection(val local: String, val remote: String, val state: String)

@Serializable
data class TcpConnections(val count: Int = 0, val connections: List<TcpConnection> = emptyList())

@Serializable
data class PingResult(
    val host: String,
    val results: List<Long?> = emptyList(),
    val avgMs: Double? = null,
    val lossPct: Double = 0.0,
)
