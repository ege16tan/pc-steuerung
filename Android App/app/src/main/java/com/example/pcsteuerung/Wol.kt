package com.example.pcsteuerung

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sendet ein Wake-on-LAN Magic Packet direkt aus dem lokalen Netz.
 *
 * Aufbau: 6 x 0xFF + 16 x MAC-Adresse (102 Bytes), per UDP-Broadcast.
 * Funktioniert auch, wenn der PC ausgeschaltet ist (Voraussetzung: WoL im
 * BIOS/UEFI und in der Netzwerkkarte aktiviert, Ethernet empfohlen).
 */
fun sendMagicPacket(mac: String, broadcast: String, port: Int = 9): Boolean {
    val macBytes = try {
        mac.trim().lowercase()
            .replace("-", ":")
            .split(":")
            .map { it.toInt(16).toByte() }
            .toByteArray()
    } catch (e: Exception) {
        return false
    }
    if (macBytes.size != 6) return false

    val packet = ByteArray(6 + 16 * 6)
    for (i in 0 until 6) packet[i] = 0xFF.toByte()
    for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)

    return try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(
                DatagramPacket(
                    packet,
                    packet.size,
                    InetAddress.getByName(broadcast.ifBlank { "255.255.255.255" }),
                    port,
                )
            )
        }
        true
    } catch (e: Exception) {
        false
    }
}
