package com.sack.pcremote.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/** RelaySocket against a WebSocket server standing in for the relay (and the PC behind it). */
class RelaySocketTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private val anywhere = InetSocketAddress.createUnresolved("pc", 47820)

    private fun relayUrl() = server.url("/connect/" + "a".repeat(64)).toString()

    private fun echoRelay() = server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }).build())

    private fun java.io.InputStream.readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = read(out, got, n - got)
            check(r > 0) { "stream ended after $got of $n bytes" }
            got += r
        }
        return out
    }

    @Test fun bytes_written_come_back_through_the_tunnel() {
        echoRelay()
        RelaySocket(relayUrl()).use { socket ->
            socket.connect(anywhere, 5000)
            val payload = Random.nextBytes(200_000)
            // Several writes, as TLS records would go.
            payload.toList().chunked(16_384).forEach { socket.getOutputStream().write(it.toByteArray()) }
            assertArrayEquals(payload, socket.getInputStream().readFully(payload.size))
        }
    }

    @Test fun a_pc_that_is_not_on_the_relay_is_reported_as_offline() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThrows(PcOfflineException::class.java) { RelaySocket(relayUrl()).connect(anywhere, 5000) }
    }

    @Test fun reads_honour_the_socket_timeout() {
        echoRelay()
        RelaySocket(relayUrl()).use { socket ->
            socket.connect(anywhere, 5000)
            socket.soTimeout = 300
            assertThrows(SocketTimeoutException::class.java) { socket.getInputStream().read(ByteArray(10)) }
        }
    }

    @Test fun the_stream_ends_when_the_relay_closes_the_tunnel() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ByteString.of(1, 2, 3))
                webSocket.close(1000, "PC gone")
            }
        }).build())
        RelaySocket(relayUrl()).use { socket ->
            socket.connect(anywhere, 5000)
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            assertArrayEquals(byteArrayOf(1, 2, 3), input.readFully(3))
            assertEquals(-1, input.read(ByteArray(10)))
        }
    }

    @Test fun the_relay_address_depends_only_on_the_first_digit_of_the_id() {
        val id = "B" + "0".repeat(63)
        assertEquals(true, Relay.urlFor(id).endsWith("/connect/" + id.lowercase()))
        assertEquals(false, Relay.urlFor(id).contains("{s}"))
    }
}
