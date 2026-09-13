package com.sack.pcremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// ══════════════════════════════════════════════════════════════
// Wake-on-LAN: "magic packet" = 6 × 0xFF + la MAC repetida 16 veces,
// por UDP broadcast. No hace falta el agente (está apagado): la
// tarjeta de red lo reconoce sola.
//
// Requisitos del lado del PC, que la app no puede cambiar: WoL
// activado en la BIOS/UEFI y en las propiedades del adaptador, y
// normalmente conexión por cable. Con "inicio rápido" de Windows
// activado, algunos equipos no despiertan desde apagado total.
// ══════════════════════════════════════════════════════════════

object WakeOnLan {

    /** Sends the packet to the subnet broadcast (if known) and to 255.255.255.255, ports 9 and 7. */
    suspend fun wake(macAddress: String, subnetBroadcast: String?) = withContext(Dispatchers.IO) {
        val packet = magicPacket(parseMac(macAddress))
        val targets = listOfNotNull(subnetBroadcast, "255.255.255.255").distinct()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (host in targets) {
                val address = InetAddress.getByName(host)
                for (port in intArrayOf(9, 7)) {
                    socket.send(DatagramPacket(packet, packet.size, address, port))
                }
            }
        }
    }

    fun parseMac(mac: String): ByteArray {
        val hex = mac.filter { it.isLetterOrDigit() }
        require(hex.length == 12 && hex.all { it in "0123456789abcdefABCDEF" }) { "MAC inválida: $mac" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { i -> mac[i % 6] }
    }
}
