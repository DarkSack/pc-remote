package com.sack.pcremote.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.sack.pcremote.data.AgentCredentials
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ══════════════════════════════════════════════════════════════
// Todo lo que la app sabe de UN PC mientras está abierto: la conexión
// (AgentClient), su info, las métricas en vivo con su historial para las
// gráficas, la latencia y los plugins que tiene activos.
//
// Vive en un ViewModel (PcViewModel): sobrevive a rotaciones y a cambiar
// de pestaña, y todas las pantallas del PC comparten UNA conexión.
//
// Ciclo de vida — el arreglo de "al volver no reconecta":
//   onBackground(): anota la hora (elapsedRealtime) y programa cortar la
//     conexión a los 30 s. Android puede congelar la app antes de que ese
//     temporizador salte, así que no se confía en él.
//   onForeground(): según cuánto tiempo pasó fuera,
//     · ≥ 30 s  → el socket no es de fiar: restart() inmediato.
//     · ≥ 5 s   → se comprueba con un ping; si no vuelve en 2,5 s, restart().
//     · y si estaba desconectado o esperando backoff, se conecta ya.
//   Mientras está abierta: un ping cada 5 s mide la latencia; dos pings
//   perdidos seguidos también fuerzan restart() (socket zombi).
//   Si falla 2 veces seguidas, se busca el PC por mDNS (por la huella de su
//   certificado): si cambió de IP, se guarda la nueva y se reintenta ya.
// ══════════════════════════════════════════════════════════════

/** Ring buffers for the dashboard charts: one point per stats sample (1 s). */
data class MetricHistory(
    val cpu: List<Float> = emptyList(),
    val ram: List<Float> = emptyList(),
    val gpu: List<Float> = emptyList(),
    val rx: List<Float> = emptyList(),
    val tx: List<Float> = emptyList(),
) {
    fun add(s: SystemStats): MetricHistory = MetricHistory(
        cpu = (cpu + s.cpu.toFloat()).takeLast(MAX),
        ram = (ram + s.ramPct.toFloat()).takeLast(MAX),
        gpu = (gpu + (s.gpu?.usage?.toFloat() ?: 0f)).takeLast(MAX),
        rx = (rx + (s.net?.rxBps?.toFloat() ?: 0f)).takeLast(MAX),
        tx = (tx + (s.net?.txBps?.toFloat() ?: 0f)).takeLast(MAX),
    )

    companion object {
        /** Two minutes at one sample a second. */
        const val MAX = 120
    }
}

class PcSession(
    context: Context,
    private val store: CredentialsStore,
    private val discovery: Discovery,
    initial: AgentCredentials,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    val deviceId: String = initial.deviceId
    val client = AgentClient(initial)

    private val _creds = MutableStateFlow(initial)
    val creds: StateFlow<AgentCredentials> = _creds.asStateFlow()

    val state: StateFlow<ConnectionState> get() = client.state
    val problem: StateFlow<ConnectionProblem?> get() = client.problem

    private val _info = MutableStateFlow<SystemInfo?>(null)
    val info: StateFlow<SystemInfo?> = _info.asStateFlow()

    private val _stats = MutableStateFlow<SystemStats?>(null)
    val stats: StateFlow<SystemStats?> = _stats.asStateFlow()

    private val _history = MutableStateFlow(MetricHistory())
    val history: StateFlow<MetricHistory> = _history.asStateFlow()

    private val _latency = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latency.asStateFlow()

    /** Wall-clock ms of the last stats sample: "última sincronización". */
    private val _lastSync = MutableStateFlow<Long?>(null)
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    /** Null until known. Agents older than 0.4 have no plugins domain: see [isAvailable]. */
    private val _plugins = MutableStateFlow<List<PluginInfo>?>(null)
    val plugins: StateFlow<List<PluginInfo>?> = _plugins.asStateFlow()

    /** True once this session connected at least once (the UI stops showing the full-screen connecting state). */
    private val _everConnected = MutableStateFlow(false)
    val everConnected: StateFlow<Boolean> = _everConnected.asStateFlow()

    private var foreground = false
    private var backgroundAt: Long? = null
    private var dropJob: Job? = null
    private var liveJob: Job? = null
    private var lastRediscovery = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Network back (Wi-Fi reconnected, switched networks): skip the rest of the backoff.
        override fun onAvailable(network: Network) = client.reconnectNow()
    }

    init {
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        }

        // Per connection: system info, plugins and (for Wake-on-LAN) the MAC.
        scope.launch {
            client.state.collect { st ->
                if (st == ConnectionState.CONNECTED) {
                    _everConnected.value = true
                    launch { loadInfo() }
                    launch { loadPlugins() }
                } else {
                    _latency.value = null
                }
            }
        }

        // Stale address: look for the PC by fingerprint after two failures in a row.
        scope.launch {
            client.failures.collect { n -> if (n >= 2) rediscover() }
        }
    }

    // ── Lifecycle (called by the PC screen's LifecycleStartEffect) ─────

    fun onForeground() {
        foreground = true
        dropJob?.cancel()
        dropJob = null
        val away = backgroundAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        backgroundAt = null

        when (client.state.value) {
            ConnectionState.DISCONNECTED -> client.connect()
            ConnectionState.FAILED -> if (client.problem.value?.isFinal != true) client.connect()
            ConnectionState.RECONNECTING -> client.reconnectNow()
            ConnectionState.CONNECTED -> when {
                away >= STALE_AFTER_MS -> client.restart()
                away >= VERIFY_AFTER_MS -> scope.launch {
                    if (client.ping(RESUME_PING_TIMEOUT_MS) == null) {
                        Log.i(TAG, "Socket did not answer after ${away / 1000}s away; restarting")
                        client.restart()
                    }
                }
            }
            ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING ->
                if (away >= STALE_AFTER_MS) client.restart()
        }
        startLive()
    }

    fun onBackground() {
        foreground = false
        backgroundAt = SystemClock.elapsedRealtime()
        liveJob?.cancel()
        liveJob = null
        // The connection follows the app: kept for a short grace period (hopping to
        // another app and back should not reconnect), then dropped. If Android
        // freezes the app first, onForeground() sees the elapsed time instead.
        dropJob?.cancel()
        dropJob = scope.launch {
            delay(STALE_AFTER_MS)
            client.disconnect()
        }
    }

    /** Stats stream + latency pings, only while the app is visible. */
    private fun startLive() {
        liveJob?.cancel()
        liveJob = scope.launch {
            launch {
                client.stream("systeminfo", "stats").collect { data ->
                    val s = client.decode(SystemStats.serializer(), data) ?: return@collect
                    _stats.value = s
                    _history.value = _history.value.add(s)
                    _lastSync.value = System.currentTimeMillis()
                }
            }
            launch {
                var missed = 0
                while (isActive) {
                    if (client.state.value == ConnectionState.CONNECTED) {
                        val rtt = client.ping(PING_TIMEOUT_MS)
                        if (rtt != null) {
                            missed = 0
                            _latency.value = rtt
                        } else if (client.state.value == ConnectionState.CONNECTED && ++missed >= 2) {
                            Log.i(TAG, "Two pings lost; restarting the connection")
                            missed = 0
                            client.restart()
                        }
                    } else {
                        missed = 0
                    }
                    delay(PING_EVERY_MS)
                }
            }
        }
    }

    // ── Actions ─────────────────────────────────────────

    /** "Reintentar": works from any state, including a non-final FAILED. */
    fun retry() {
        when (client.state.value) {
            ConnectionState.DISCONNECTED, ConnectionState.FAILED -> client.connect()
            else -> client.restart()
        }
    }

    /** Saves a new address typed in Settings and reconnects there. */
    fun changeAddress(host: String, port: Int) {
        val updated = _creds.value.copy(agentHost = host.trim(), agentPort = port)
        store.save(updated)
        _creds.value = updated
        client.updateEndpoint(updated.agentHost, updated.agentPort)
        client.disconnect()
        client.connect()
    }

    fun unpair() {
        client.disconnect()
        store.delete(deviceId)
    }

    /** Reloads the plugin list (e.g. after the user enabled one in the PC's panel). */
    fun refreshPlugins() { scope.launch { loadPlugins() } }

    /**
     * Whether a feature can be used on this PC. Agents older than 0.4 do not have
     * the plugins domain: the features they had are assumed on, the newer ones off.
     */
    fun isAvailable(domain: String): Boolean {
        val list = _plugins.value ?: return domain in LEGACY_DOMAINS
        return list.any { it.domain.equals(domain, ignoreCase = true) && it.enabled && it.loaded }
    }

    fun close() {
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        client.close()
    }

    // ── Internals ───────────────────────────────────────

    private suspend fun loadInfo() {
        val info = client.call("systeminfo", "info", SystemInfo.serializer()).getOrNull() ?: return
        _info.value = info
        // Remember MAC + broadcast so the PC can be woken up later from the list.
        val latest = store.load(deviceId) ?: return
        if (info.macAddress != null && (latest.macAddress != info.macAddress || latest.broadcast != info.broadcast)) {
            val updated = latest.copy(macAddress = info.macAddress, broadcast = info.broadcast)
            store.save(updated)
            _creds.value = updated
        }
    }

    private suspend fun loadPlugins() {
        val res = client.call("plugins", "list", PluginList.serializer())
        res.onSuccess { _plugins.value = it.plugins }
        res.onFailure { e ->
            // INVALID_COMMAND = an agent without plugins: keep null (legacy set).
            if (e is AgentException) _plugins.value = null
        }
    }

    private fun rediscover() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRediscovery < REDISCOVER_EVERY_MS) return
        lastRediscovery = now
        scope.launch {
            val current = client.creds
            val found = runCatching { discovery.find(current.certFingerprintHex) }.getOrNull() ?: return@launch
            if (found.host == current.agentHost && found.port == current.agentPort) return@launch
            Log.i(TAG, "PC found at a new address ${found.host}:${found.port}")
            val updated = (store.load(deviceId) ?: current).copy(agentHost = found.host, agentPort = found.port)
            store.save(updated)
            _creds.value = updated
            client.updateEndpoint(found.host, found.port)
            client.reconnectNow()
        }
    }

    companion object {
        private const val TAG = "PcSession"
        private const val STALE_AFTER_MS = 30_000L
        private const val VERIFY_AFTER_MS = 5_000L
        private const val RESUME_PING_TIMEOUT_MS = 2_500L
        private const val PING_EVERY_MS = 5_000L
        private const val PING_TIMEOUT_MS = 4_000L
        private const val REDISCOVER_EVERY_MS = 60_000L

        /** What agents before the plugins system (≤ 0.3) could do. */
        val LEGACY_DOMAINS = setOf(
            "system", "systeminfo", "input", "clipboard", "media", "windows", "processes", "applications", "appicons", "ping",
        )
    }
}
