package com.sack.pcremote.net

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ══════════════════════════════════════════════════════════════
// Plan B para encontrar un PC emparejado cuando mDNS no responde
// (routers que filtran multicast, Wi-Fi de invitados, algunos
// repetidores): recorrer la subred del móvil probando el puerto del
// agente, y quedarse con la IP cuyo certificado TLS tiene la huella
// guardada al emparejar.
//
// La huella es la que manda: una IP que responde en ese puerto con
// otro certificado no se acepta nunca. Además la conexión real sigue
// pasando por el pinning de AgentClient.
// ══════════════════════════════════════════════════════════════

object LanScan {
    private const val TAG = "LanScan"
    private const val CONNECT_TIMEOUT_MS = 350
    private const val HANDSHAKE_TIMEOUT_MS = 2500
    private const val PARALLEL = 64

    /** The phone's IPv4 on the active network and its prefix (e.g. 192.168.1.37/24), or null. */
    fun phoneSubnet(context: Context): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
            ?.let { (it.address as Inet4Address) to it.prefixLength }
    }

    /** True if [address] is in the same IPv4 subnet as the phone. */
    fun sameSubnet(context: Context, address: java.net.InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val (phone, prefix) = phoneSubnet(context) ?: return false
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (toInt(phone) and mask) == (toInt(address) and mask)
    }

    /**
     * The address of the agent with certificate [fingerprintHex] on the phone's subnet,
     * or null. Big subnets are narrowed to the phone's /24 (a /16 would be 65k probes).
     */
    suspend fun find(context: Context, port: Int, fingerprintHex: String, skip: String? = null): String? {
        val (phone, prefix) = phoneSubnet(context) ?: return null
        val bits = prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - bits)
        val base = toInt(phone) and mask
        val own = toInt(phone)
        val hosts = (1 until (1 shl (32 - bits)) - 1).map { base + it }.filter { it != own }

        Log.i(TAG, "Scanning ${hosts.size} hosts around ${phone.hostAddress}/$bits:$port")
        val gate = Semaphore(PARALLEL)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                // Ordered by distance to the phone's address: DHCP tends to hand out neighbours.
                hosts.sortedBy { kotlin.math.abs(it - own) }
                    .map { ip -> async { gate.withPermit { probe(fromInt(ip), port, fingerprintHex) } } }
                    .awaitAll()
                    .firstOrNull { it != null && it != skip }
            }
        }
    }

    /** The host if something listens on [port] and presents the expected certificate. */
    private fun probe(host: String, port: Int, fingerprintHex: String): String? {
        val raw = Socket()
        return try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS
            val tls = trustAll.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.use {
                it.startHandshake()
                val cert = it.session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
                val fp = Crypto.toHex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
                if (fp.equals(fingerprintHex, ignoreCase = true)) host else null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { raw.close() }
        }
    }

    /** Only reads the certificate to compare its hash; nothing is sent over this socket. */
    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }
    }

    private fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }

    private fun fromInt(v: Int): String = "${v ushr 24 and 0xff}.${v ushr 16 and 0xff}.${v ushr 8 and 0xff}.${v and 0xff}"
}
