package com.sack.pcremote.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWsUrlTest {
    @Test fun `ipv4 and hostnames are used as-is`() {
        assertEquals("wss://192.168.1.50:47820/ws", agentWsUrl("192.168.1.50", 47820))
        assertEquals("wss://pc-sala.local:47820/ws", agentWsUrl("pc-sala.local", 47820))
    }

    @Test fun `ipv6 literals get brackets and lose the scope id`() {
        assertEquals("wss://[fe80::1c2b:3aff:fe4d:5e6f]:47820/ws", agentWsUrl("fe80::1c2b:3aff:fe4d:5e6f%wlan0", 47820))
        assertEquals("wss://[2001:db8::1]:1/ws", agentWsUrl("2001:db8::1", 1))
    }

    @Test fun `already bracketed ipv6 is left alone`() {
        assertEquals("wss://[2001:db8::1]:47820/ws", agentWsUrl("[2001:db8::1]", 47820))
    }
}
