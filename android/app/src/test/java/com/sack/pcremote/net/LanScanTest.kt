package com.sack.pcremote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScanTest {
    @Test
    fun parsesOnlyDottedIpv4WithoutLookingUpNames() {
        assertEquals((192 shl 24) or (168 shl 16) or (100 shl 8) or 7, LanScan.parseIpv4("192.168.100.7"))
        assertNull(LanScan.parseIpv4("pc-sackito.local"))
        assertNull(LanScan.parseIpv4("192.168.1"))
        assertNull(LanScan.parseIpv4("192.168.1.256"))
        assertNull(LanScan.parseIpv4("fe80::1"))
    }

    @Test
    fun scansTheOldPcSubnetOnlyWhenItIsPrivate() {
        fun private(ip: String) = LanScan.isPrivate(LanScan.parseIpv4(ip)!!)
        assertTrue(private("10.4.0.9"))
        assertTrue(private("172.16.0.1"))
        assertTrue(private("172.31.255.254"))
        assertTrue(private("192.168.1.20"))
        assertFalse(private("172.32.0.1"))
        assertFalse(private("100.101.2.3")) // Tailscale / carrier NAT
        assertFalse(private("8.8.8.8"))
    }
}
