package com.sack.pcremote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionProblemTest {

    @Test fun `refused connection says the agent is not running`() {
        val p = ConnectionProblem.from(ConnectException("failed to connect to /192.168.1.50 (port 47820): connect failed: ECONNREFUSED (Connection refused)"))
        assertEquals(ConnectionProblem.Kind.REFUSED, p.kind)
        assertFalse(p.isFinal)
        // The technical text is kept for "Ver detalles", not shown as the message.
        assertTrue(p.detail!!.contains("ECONNREFUSED"))
        assertFalse(p.message.contains("ECONNREFUSED"))
    }

    @Test fun `no route points at the wifi`() {
        assertEquals(ConnectionProblem.Kind.NO_NETWORK, ConnectionProblem.from(NoRouteToHostException("No route to host")).kind)
        assertEquals(ConnectionProblem.Kind.NO_NETWORK,
            ConnectionProblem.from(ConnectException("connect failed: EHOSTUNREACH (No route to host)")).kind)
    }

    @Test fun `timeouts and dead sockets are retried`() {
        assertEquals(ConnectionProblem.Kind.TIMEOUT, ConnectionProblem.from(SocketTimeoutException("connect timed out")).kind)
        val pong = ConnectionProblem.from(IOException("sent ping but didn't receive pong within 10000ms (after 3 successful ping/pongs)"))
        assertEquals(ConnectionProblem.Kind.TIMEOUT, pong.kind)
        assertFalse(pong.isFinal)
        assertEquals(ConnectionProblem.Kind.CLOSED, ConnectionProblem.from(EOFException()).kind)
        assertEquals(ConnectionProblem.Kind.UNREACHABLE, ConnectionProblem.from(UnknownHostException("pc.local")).kind)
    }

    @Test fun `a certificate mismatch is final even when wrapped`() {
        val wrapped = SSLHandshakeException("handshake failed").apply { initCause(CertificateMismatchException("got=aa expected=bb")) }
        val p = ConnectionProblem.from(wrapped)
        assertEquals(ConnectionProblem.Kind.CERTIFICATE, p.kind)
        assertTrue(p.isFinal)
    }

    @Test fun `revoked and bad address are final`() {
        assertTrue(ConnectionProblem.revoked().isFinal)
        assertTrue(ConnectionProblem.badAddress("fe80::1").isFinal)
        assertNotNull(ConnectionProblem.from(RuntimeException("boom")).detail)
    }
}
