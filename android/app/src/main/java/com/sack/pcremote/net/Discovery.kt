package com.sack.pcremote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// ══════════════════════════════════════════════════════════════
// mDNS discovery vía NsdManager (built-in Android — sin lib externa).
// El agente publica _pcremote._tcp con TXT { hostname, os, version }.
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
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, resolveListener(this@callbackFlow.channel))
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) { close(t) }

        awaitClose {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Throwable) {}
        }
    }

    private fun resolveListener(out: SendChannel<DiscoveredAgent>) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val txt  = serviceInfo.attributes ?: emptyMap()
            fun readTxt(key: String) = txt[key]?.toString(Charsets.UTF_8)
            out.trySend(DiscoveredAgent(
                name    = serviceInfo.serviceName ?: host,
                host    = host,
                port    = port,
                os      = readTxt("os"),
                version = readTxt("version"),
            ))
        }
    }

    companion object {
        const val SERVICE_TYPE = "_pcremote._tcp."
    }
}
