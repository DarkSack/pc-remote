package com.sack.pcremote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

// ══════════════════════════════════════════════════════════════
// mDNS discovery vía NsdManager (built-in Android — sin lib externa).
// El agente publica _pcremote._tcp con TXT { hostname, os, version, fp }.
//
// Las resoluciones van DE UNA EN UNA. Antes de Android 14, NsdManager
// solo admite una resolveService a la vez: la segunda falla con
// FAILURE_ALREADY_ACTIVE y ese PC no aparecía nunca. Con dos agentes
// en la red, uno de los dos se perdía.
// ══════════════════════════════════════════════════════════════

data class DiscoveredAgent(
    val name: String,
    val host: String,
    val port: Int,
    val os: String? = null,
    val version: String? = null,
    /** SHA-256 of the agent's certificate (agents >= 0.3); lets a paired PC be recognised after an IP change. */
    val fingerprint: String? = null,
)

class Discovery(private val context: Context) {

    private val nsd: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /** Emite cada agente resuelto. Se detiene al cancelar el Flow. */
    fun scan(): Flow<DiscoveredAgent> = callbackFlow {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.trySend(serviceInfo) }
        }

        val resolver = launch {
            for (info in found) {
                // A resolve that never calls back would block the queue for good.
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) }?.let { send(it) }
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) { close(t) }

        awaitClose {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Throwable) {}
            found.close()
            resolver.cancel()
        }
    }

    @Suppress("DEPRECATION") // resolveService: its replacement needs API 34.
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredAgent? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!cont.isActive) return
                    val host = pickAddress(serviceInfo)?.hostAddress
                    if (host == null) { cont.resume(null); return }
                    val txt = serviceInfo.attributes ?: emptyMap()
                    fun readTxt(key: String) = txt[key]?.toString(Charsets.UTF_8)
                    cont.resume(DiscoveredAgent(
                        name        = serviceInfo.serviceName ?: host,
                        host        = host,
                        port        = serviceInfo.port,
                        os          = readTxt("os"),
                        version     = readTxt("version"),
                        fingerprint = readTxt("fp")?.takeIf { it.length == 64 },
                    ))
                }
            })
        }

    /**
     * Prefers IPv4. The agent answers mDNS with A and AAAA records, and the first
     * address Android hands back can be an IPv6 link-local one ("fe80::…%wlan0"):
     * it needs a scope id that URLs cannot carry, so connecting to it fails.
     */
    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): InetAddress? {
        val all: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { !it.isLinkLocalAddress } ?: all.firstOrNull()
    }

    companion object {
        const val SERVICE_TYPE = "_pcremote._tcp."
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}

/** `wss://host:port/ws`, with IPv6 literals in brackets (without them OkHttp rejects the URL and throws). */
fun agentWsUrl(host: String, port: Int): String {
    val h = if (host.contains(':') && !host.startsWith("[")) "[${host.substringBefore('%')}]" else host
    return "wss://$h:$port/ws"
}
