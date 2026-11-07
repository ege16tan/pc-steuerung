package com.example.pcsteuerung

import android.util.Log
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
    val packet = buildMagicPacket(mac) ?: return false

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
        Log.d("PCSteuerung", "WoL an $mac via ${broadcast.ifBlank { "255.255.255.255" }}:$port -> ok")
        true
    } catch (e: Exception) {
        Log.e("PCSteuerung", "WoL an $mac fehlgeschlagen", e)
        false
    }
}
