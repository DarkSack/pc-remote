package com.sack.pcremote.session

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sack.pcremote.PcRemoteApplication
import com.sack.pcremote.data.AgentCredentials
import com.sack.pcremote.net.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ══════════════════════════════════════════════════════════════
// Una sesión con un PC: la conexión (AgentClient) y el estado que
// comparten todas las pestañas (Inicio, Control, Apps, Actividad,
// Ajustes) y las pantallas secundarias.
//
// Vive en el grafo de navegación del PC, así que cambiar de pestaña no
// abre ni autentica otra conexión.
//
// Lo que hace para que "volver" funcione:
//   - onForeground(): cancela el cierre diferido y comprueba que el
//     socket siga vivo (ensureAlive) o reintenta ya, sin esperar backoff.
//   - onBackground(): cierra la conexión tras 30 s en segundo plano.
//   - Si falla dos veces seguidas, busca el PC por mDNS (por la huella
//     de su certificado): si cambió de IP, guarda la nueva y reconecta.
//   - Red de vuelta (Wi-Fi) → reintento inmediato.
// ══════════════════════════════════════════════════════════════

/** How long the PC connection survives with the app in the background. */
private const val BACKGROUND_GRACE_MS = 30_000L

/** Samples kept for the charts: 10 minutes at one every 2 s. */
const val HISTORY_SIZE = 300
const val STATS_INTERVAL_MS = 2000

val AgentJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

class PcSession(app: Application, val deviceId: String) : AndroidViewModel(app) {

    private val deps = app as PcRemoteApplication
    private val store = deps.store

    val initialCreds: AgentCredentials? = store.load(deviceId)
    private val _creds = MutableStateFlow(initialCreds)
    val creds: StateFlow<AgentCredentials?> = _creds.asStateFlow()

    val client: AgentClient? = initialCreds?.let { AgentClient(it) }

    val state: StateFlow<ConnectionState> = client?.state ?: MutableStateFlow(ConnectionState.FAILED)
    val error: StateFlow<AgentError?> = client?.error ?: MutableStateFlow(null)
    val latency: StateFlow<Long?> = client?.latency ?: MutableStateFlow(null)
    val connectedSince: StateFlow<Long?> = client?.connectedSince ?: MutableStateFlow(null)

    private val _info = MutableStateFlow<SystemInfo?>(null)
    val info: StateFlow<SystemInfo?> = _info.asStateFlow()

    private val _stats = MutableStateFlow<SystemStats?>(null)
    val stats: StateFlow<SystemStats?> = _stats.asStateFlow()

    val history = MetricHistory(HISTORY_SIZE)

    private val _activity = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val activity: StateFlow<List<ActivityEvent>> = _activity.asStateFlow()
    private val _activityLoaded = MutableStateFlow(false)
    val activityLoaded: StateFlow<Boolean> = _activityLoaded.asStateFlow()

    private val _plugins = MutableStateFlow<PluginCatalog?>(null)
    val plugins: StateFlow<PluginCatalog?> = _plugins.asStateFlow()

    /** One-off messages for a snackbar: alerts from the PC, connection lost. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Terminal output and command history, alive while this PC is open. */
    val terminal = TerminalSession()

    /** "Buscando el PC en la red…" while mDNS runs after failures. */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var backgroundDrop: Job? = null
    private var rediscovery: Job? = null
    private var localEventId = -1L

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { client?.reconnectNow() }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        val c = client
        if (c != null) {
            viewModelScope.launch { c.state.collectLatest { onState(it) } }
            viewModelScope.launch {
                c.failures.collect { n -> if (n >= 2 && rediscovery?.isActive != true) rediscover() }
            }
        }
    }

    // ── lifecycle ───────────────────────────────────────
    fun onForeground() {
        backgroundDrop?.cancel()
        backgroundDrop = null
        val c = client ?: return
        // FAILED waits for the user ("Reintentar"): a revoked device must not retry on every return.
        if (c.state.value == ConnectionState.FAILED) return
        c.ensureAlive()
    }

    fun onBackground() {
        backgroundDrop?.cancel()
        backgroundDrop = viewModelScope.launch {
            delay(BACKGROUND_GRACE_MS)
            client?.disconnect()
        }
    }

    fun retry() {
        _messages.tryEmit("Reintentando…")
        client?.retryNow() ?: return
        if (rediscovery?.isActive != true) rediscover()
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        terminal.cancel?.invoke()
        client?.close()
    }

    // ── per-connection work ─────────────────────────────
    private var wasConnected = false

    /** Runs while [s] holds; collectLatest cancels it when the state changes. */
    private suspend fun onState(s: ConnectionState) {
        val c = client ?: return
        if (s != ConnectionState.CONNECTED) {
            if (wasConnected && s == ConnectionState.RECONNECTING) {
                addLocal("connection", "Conexión perdida", c.error.value?.title, "warning")
            }
            wasConnected = false
            return
        }
        if (!wasConnected) addLocal("connection", "Conectado a ${displayName()}", c.host, "success")
        wasConnected = true

        viewModelScope.launch { loadInfo(c) }
        viewModelScope.launch { refreshPlugins() }

        val stats = c.subscribe("systeminfo", "stats", buildJsonObject { put("intervalMs", STATS_INTERVAL_MS) }) { data ->
            runCatching { AgentJson.decodeFromJsonElement(SystemStats.serializer(), data) }.getOrNull()?.let {
                _stats.value = it
                history.add(it)
            }
        }
        val activity = c.subscribe("activity", "watch", buildJsonObject { put("limit", 150) },
            onError = { _activityLoaded.value = true }) { data -> onActivity(data) }
        try {
            awaitCancellation()
        } finally {
            stats.cancel()
            activity.cancel()
        }
    }

    private suspend fun loadInfo(c: AgentClient) {
        val i = runCatching { decode(c.call("systeminfo", "info"), SystemInfo.serializer()) }.getOrNull() ?: return
        _info.value = i
        // Remember MAC + broadcast so the PC can be woken up later from the list.
        val latest = store.load(deviceId) ?: return
        if (i.macAddress != null && (latest.macAddress != i.macAddress || latest.broadcast != i.broadcast)) {
            val updated = latest.copy(macAddress = i.macAddress, broadcast = i.broadcast)
            store.save(updated)
            _creds.value = updated
        }
    }

    suspend fun refreshPlugins() {
        val c = client ?: return
        runCatching { decode(c.call("plugins", "list"), PluginCatalog.serializer()) }
            .onSuccess { _plugins.value = it }
    }

    private fun onActivity(data: JsonElement) {
        val obj = runCatching { data.jsonObject }.getOrNull() ?: return
        when (obj["op"]?.jsonPrimitive?.content) {
            "snapshot" -> {
                val events = runCatching {
                    AgentJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ActivityEvent.serializer()), obj["events"]!!)
                }.getOrDefault(emptyList())
                // Keep local (negative id) events, drop the PC's older copy.
                _activity.value = (events + _activity.value.filter { it.id < 0 }).sortedByDescending { it.ts }.take(300)
                _activityLoaded.value = true
            }
            "add" -> {
                val ev = runCatching { AgentJson.decodeFromJsonElement(ActivityEvent.serializer(), obj["event"]!!) }.getOrNull() ?: return
                _activity.value = (listOf(ev) + _activity.value.filter { it.id != ev.id }).take(300)
                if (ev.type == "alert" && deps.settings.state.value.alerts) _messages.tryEmit(ev.title)
            }
        }
    }

    private fun addLocal(type: String, title: String, detail: String?, severity: String) {
        val ev = ActivityEvent(localEventId--, System.currentTimeMillis(), type, title, detail, severity)
        _activity.value = (listOf(ev) + _activity.value).take(300)
    }

    // ── rediscovery ─────────────────────────────────────
    /**
     * The saved address stopped working: look for the PC by its certificate
     * fingerprint (never by name, trivial to fake) and move to where it is now.
     */
    private fun rediscover() {
        val c = client ?: return
        val fp = _creds.value?.certFingerprintHex ?: return
        rediscovery = viewModelScope.launch {
            _searching.value = true
            try {
                val found = withTimeoutOrNull(15_000) {
                    deps.discovery.scan().first { it.fingerprint?.equals(fp, ignoreCase = true) == true }
                }
                val cur = store.load(deviceId)
                if (found != null && cur != null && (found.host != cur.agentHost || found.port != cur.agentPort)) {
                    Log.i("PcSession", "PC moved to ${found.host}:${found.port}")
                    val updated = cur.copy(agentHost = found.host, agentPort = found.port)
                    store.save(updated)
                    _creds.value = updated
                    addLocal("connection", "El PC cambió de dirección", "${found.host}:${found.port}", "info")
                    c.updateAddress(found.host, found.port)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("PcSession", "Rediscovery failed", t)
            } finally {
                _searching.value = false
            }
        }
    }

    // ── helpers for screens ─────────────────────────────
    fun displayName(): String = _info.value?.hostname ?: _creds.value?.agentName ?: "PC"

    suspend fun <T> call(domain: String, action: String, params: JsonElement? = null, serializer: KSerializer<T>, timeoutMs: Long = 8000): T {
        val c = client ?: throw IllegalStateException("Not connected")
        return decode(c.call(domain, action, params, timeoutMs), serializer)
    }

    suspend fun run(domain: String, action: String, params: JsonElement? = null, timeoutMs: Long = 8000): JsonElement? {
        val c = client ?: throw IllegalStateException("Not connected")
        return c.call(domain, action, params, timeoutMs)
    }

    fun unpair() {
        client?.disconnect()
        store.delete(deviceId)
    }

    fun post(message: String) { _messages.tryEmit(message) }

    private fun <T> decode(data: JsonElement?, serializer: KSerializer<T>): T =
        AgentJson.decodeFromJsonElement(serializer, data ?: throw RequestException("EMPTY", "Respuesta vacía del PC"))
}

/**
 * Fixed-size history of the metrics the charts draw. Snapshot-style: [version]
 * changes on every add, so a chart recomposes by reading it.
 */
class MetricHistory(val capacity: Int) {
    val cpu = Series(capacity)
    val ram = Series(capacity)
    val gpu = Series(capacity)
    val down = Series(capacity)
    val up = Series(capacity)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun add(s: SystemStats) {
        cpu.add(s.cpu.toFloat())
        ram.add(s.ramPct.toFloat())
        gpu.add(s.gpu?.usage?.toFloat() ?: Float.NaN)
        down.add((s.net?.downBps ?: 0).toFloat())
        up.add((s.net?.upBps ?: 0).toFloat())
        _version.value++
    }

    class Series(private val capacity: Int) {
        private val data = FloatArray(capacity)
        private var start = 0
        var size = 0
            private set

        @Synchronized fun add(v: Float) {
            if (size < capacity) data[(start + size++) % capacity] = v
            else { data[start] = v; start = (start + 1) % capacity }
        }

        /** Oldest first. */
        @Synchronized fun toList(): List<Float> = List(size) { data[(start + it) % capacity] }
    }
}
