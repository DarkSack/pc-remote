package com.sack.pcremote.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeOnLanTest {

    @Test fun `parses the MAC format the agent sends and common variants`() {
        val expected = byteArrayOf(0x58, 0x11, 0x22, 0xA6.toByte(), 0xDD.toByte(), 0x4F)
        assertArrayEquals(expected, WakeOnLan.parseMac("58:11:22:A6:DD:4F"))
        assertArrayEquals(expected, WakeOnLan.parseMac("58-11-22-a6-dd-4f"))
        assertArrayEquals(expected, WakeOnLan.parseMac("581122A6DD4F"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a short MAC`() { WakeOnLan.parseMac("58:11:22:A6:DD") }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-hex MAC`() { WakeOnLan.parseMac("GG:11:22:A6:DD:4F") }

    @Test fun `magic packet is 6x FF followed by the MAC 16 times`() {
        val mac = WakeOnLan.parseMac("58:11:22:A6:DD:4F")
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        (0 until 6).forEach { assertEquals(0xFF.toByte(), packet[it]) }
        (0 until 16).forEach { rep ->
            assertArrayEquals(mac, packet.copyOfRange(6 + rep * 6, 12 + rep * 6))
        }
    }
}
