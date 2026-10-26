package com.sack.pcremote.net

import com.sack.pcremote.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

// ══════════════════════════════════════════════════════════════
// Relay: el camino cuando el móvil no llega al PC (cortafuegos del
// antivirus, dos routers, otra red). Móvil y PC solo hacen conexiones
// de salida al relay, que copia bytes entre los dos.
//
// RelaySocket se hace pasar por un socket TCP: OkHttp le pone encima el
// mismo TLS fijado a la huella del PC, así que autenticación, streams y
// pantalla remota funcionan sin cambios y el relay solo ve texto cifrado.
// ══════════════════════════════════════════════════════════════

object Relay {
    /**
     * The relay for a PC. `{s}` = first hex digit of its id (the pinned fingerprint): the PC
     * computes the same, so a group of PCs can move to another relay server by DNS alone.
     */
    fun urlFor(fingerprintHex: String): String {
        val id = fingerprintHex.lowercase()
        return BuildConfig.RELAY_URL.replace("{s}", id.take(1)).trimEnd('/') + "/connect/" + id
    }

    /** Plain client to the relay itself (public certificate, system trust). Shared: one pool and dispatcher. */
    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

/** Gives OkHttp [RelaySocket]s instead of TCP sockets; the address OkHttp asks for is ignored. */
class RelaySocketFactory(private val url: String) : SocketFactory() {
    override fun createSocket(): Socket = RelaySocket(url)
    override fun createSocket(host: String?, port: Int): Socket = unconnectedOnly()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unconnectedOnly()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unconnectedOnly()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = unconnectedOnly()
    private fun unconnectedOnly(): Nothing = throw UnsupportedOperationException("OkHttp only asks for unconnected sockets")
}

/** The relay answered 404: the PC is not connected to it (off, asleep, no internet). */
class PcOfflineException : IOException("El PC no está conectado al relay")

/**
 * A byte stream through the relay, as a [Socket]. Reads block on a bounded queue: when the
 * app reads slowly, OkHttp's reader thread blocks too and TCP slows the PC down, instead of
 * frames piling up in memory.
 */
class RelaySocket(private val url: String) : Socket() {
    private val inbox = LinkedBlockingQueue<ByteArray>(256)
    private val opened = CountDownLatch(1)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var open = false
    @Volatile private var closed = false
    @Volatile private var eof = false
    @Volatile private var timeoutMs = 0
    private var endpoint: InetSocketAddress? = null

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        this.endpoint = endpoint as? InetSocketAddress
        val limit = if (timeout > 0) timeout.toLong() else 15_000L
        val client = Relay.http.newBuilder().connectTimeout(limit, TimeUnit.MILLISECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed) inbox.put(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = when (response?.code) {
                    404 -> PcOfflineException()
                    null -> t as? IOException ?: IOException(t)
                    else -> IOException("Relay: HTTP ${response.code}", t)
                }
                opened.countDown()
                finish()
            }
        })
        if (!opened.await(limit, TimeUnit.MILLISECONDS)) {
            ws?.cancel()
            throw SocketTimeoutException("Relay: sin respuesta en $limit ms")
        }
        failure?.let { throw it }
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    /** End of stream: wakes a blocked reader without waiting for queue space. */
    private fun finish() {
        eof = true
        inbox.offer(EOF)
    }

    private val input = object : InputStream() {
        private var chunk: ByteArray = EMPTY
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) {
                val next = nextChunk() ?: run {
                    failure?.let { throw it }
                    return -1
                }
                chunk = next
                pos = 0
            }
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = chunk.size - pos

        /** The next chunk, or null at the end. Short polls so an end that could not be queued (full queue) is still seen. */
        private fun nextChunk(): ByteArray? {
            val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000L else Long.MAX_VALUE
            while (true) {
                if (closed) throw IOException("Socket closed")
                val next = inbox.poll(100, TimeUnit.MILLISECONDS)
                if (next === EOF) return null
                if (next != null) return next
                if (eof && inbox.isEmpty()) return null
                if (System.nanoTime() >= deadline) throw SocketTimeoutException("Read timed out")
            }
        }
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val socket = ws
            if (closed || eof || socket == null) throw IOException("Relay connection closed")
            // OkHttp queues sends without limit (16 MB, then it closes): wait while 1 MB is pending.
            while (socket.queueSize() > MAX_QUEUED && !closed && !eof) Thread.sleep(2)
            if (!socket.send(b.toByteString(off, len))) throw IOException("Relay connection closed")
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output

    override fun close() {
        if (closed) return
        closed = true
        // Frees OkHttp's reader if it is blocked on a full queue; later frames are dropped.
        inbox.clear()
        ws?.let { if (!it.close(1000, null)) it.cancel() }
        finish()
    }

    override fun isConnected() = open
    override fun isClosed() = closed
    override fun isInputShutdown() = closed || eof
    override fun isOutputShutdown() = closed || eof
    override fun shutdownInput() = close()
    override fun shutdownOutput() = close()
    override fun setSoTimeout(timeout: Int) { timeoutMs = timeout }
    override fun getSoTimeout() = timeoutMs
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun setKeepAlive(on: Boolean) = Unit
    override fun getInetAddress(): InetAddress? = endpoint?.address
    override fun getPort() = endpoint?.port ?: 0
    override fun getRemoteSocketAddress(): SocketAddress? = endpoint
    override fun toString() = "RelaySocket[$url]"

    private companion object {
        val EOF = ByteArray(0)
        val EMPTY = ByteArray(0)
        const val MAX_QUEUED = 1L shl 20
    }
}
