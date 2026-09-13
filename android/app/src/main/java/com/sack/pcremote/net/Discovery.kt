package com.sack.pcremote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// ══════════════════════════════════════════════════════════════
// mDNS discovery vía NsdManager (built-in Android — sin lib externa).
// El agente publica _pcremote._tcp con TXT { hostname, os, version }.
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

    @Suppress("DEPRECATION") // resolveService / host: replacements need API 34.
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredAgent? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!cont.isActive) return
                    val host = serviceInfo.host?.hostAddress
                    if (host == null) { cont.resume(null); return }
                    val txt = serviceInfo.attributes ?: emptyMap()
                    fun readTxt(key: String) = txt[key]?.toString(Charsets.UTF_8)
                    cont.resume(DiscoveredAgent(
                        name    = serviceInfo.serviceName ?: host,
                        host    = host,
                        port    = serviceInfo.port,
                        os      = readTxt("os"),
                        version = readTxt("version"),
                    ))
                }
            })
        }

    companion object {
        const val SERVICE_TYPE = "_pcremote._tcp."
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
