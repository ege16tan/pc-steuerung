package com.example.pcsteuerung

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Sendet ein Wake-on-LAN Magic Packet direkt aus dem lokalen Netz.
 *
 * Aufbau: 6 x 0xFF + 16 x MAC-Adresse (102 Bytes), per UDP-Broadcast.
 * Funktioniert auch, wenn der PC ausgeschaltet ist (Voraussetzung: WoL im
 * BIOS/UEFI und in der Netzwerkkarte aktiviert, Ethernet empfohlen).
 *
 * Zuverlaessigkeit: Die begrenzte Broadcast-Adresse "255.255.255.255" wird von
 * vielen Routern/Access-Points nicht an kabelgebundene Geraete weitergeleitet.
 * Deshalb wird - sofern moeglich - die gerichtete Broadcast-Adresse des
 * aktuellen WLAN-Subnetzes (z. B. 192.168.1.255) ermittelt und bevorzugt.
 * Ausserdem wird das Paket mehrfach gesendet und der Socket an das WLAN-Netz
 * gebunden, damit es nicht versehentlich ueber das Mobilfunknetz geht.
 */
fun sendMagicPacket(context: Context, mac: String, broadcast: String, port: Int = 9): Boolean {
    val packet = buildMagicPacket(mac) ?: return false
    val targets = resolveTargets(context, broadcast)
    if (targets.isEmpty()) {
        Log.e(TAG, "WoL an $mac: keine gueltige Broadcast-Adresse")
        return false
    }

    var sentAny = false
    for (target in targets) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true

                // An das aktive WLAN-Netz binden, damit das Paket sicher ueber
                // WLAN statt ueber eine aktive Mobilfunkverbindung gesendet wird.
                wifiNetwork(context)?.let { network ->
                    try {
                        network.bindSocket(socket)
                    } catch (e: Exception) {
                        Log.w(TAG, "Socket konnte nicht an WLAN gebunden werden", e)
                    }
                }

                repeat(SEND_COUNT) { i ->
                    socket.send(DatagramPacket(packet, packet.size, target, port))
                    if (i < SEND_COUNT - 1) Thread.sleep(SEND_DELAY_MS)
                }
            }
            Log.d(TAG, "WoL an $mac via ${target.hostAddress}:$port (${SEND_COUNT}x) -> ok")
            sentAny = true
        } catch (e: Exception) {
            Log.e(TAG, "WoL an $mac via $target:$port fehlgeschlagen", e)
        }
    }
    return sentAny
}

/**
 * Bestimmt die Ziel-Adressen, an die das Magic Packet gesendet wird.
 *
 * - Hat der Nutzer eine konkrete Broadcast-Adresse eingetragen (z. B.
 *   192.168.1.255), wird genau diese verwendet.
 * - Bleibt die Einstellung auf dem Standard "255.255.255.255", wird die
 *   gerichtete Broadcast-Adresse des WLAN-Subnetzes bevorzugt und die
 *   begrenzte Adresse als Fallback zusaetzlich versucht.
 */
private fun resolveTargets(context: Context, broadcast: String): List<InetAddress> {
    val configured = broadcast.trim().ifBlank { DEFAULT_BROADCAST }
    val targets = mutableListOf<InetAddress>()

    if (configured == DEFAULT_BROADCAST) {
        directedBroadcast(context)?.let { targets.add(it) }
    }

    try {
        targets.add(InetAddress.getByName(configured))
    } catch (e: Exception) {
        Log.e(TAG, "Ungueltige Broadcast-Adresse: $configured", e)
    }

    return targets.distinctBy { it.hostAddress }
}

/**
 * Ermittelt die gerichtete Broadcast-Adresse des aktuellen WLAN-Subnetzes,
 * z. B. 192.168.1.255 fuer 192.168.1.42/24. Liefert null, wenn keine
 * WLAN-Verbindung oder keine IPv4-Adresse vorhanden ist.
 */
private fun directedBroadcast(context: Context): InetAddress? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

    val linkProps = cm.getLinkProperties(network) ?: return null
    val link = linkProps.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
    return broadcastOf(link)
}

private fun broadcastOf(link: LinkAddress): InetAddress? {
    val ip = link.address as? Inet4Address ?: return null
    val prefixLength = link.prefixLength.coerceIn(0, 32)

    val ipInt = bytesToInt(ip.address)
    val mask: Int = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
    val broadcastInt = (ipInt and mask) or mask.inv()
    return InetAddress.getByAddress(intToBytes(broadcastInt))
}

private fun wifiNetwork(context: Context): Network? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) network else null
}

private fun bytesToInt(bytes: ByteArray): Int {
    var result = 0
    for (i in 0 until 4) {
        result = (result shl 8) or (bytes[i].toInt() and 0xFF)
    }
    return result
}

private fun intToBytes(value: Int): ByteArray = byteArrayOf(
    ((value ushr 24) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

private const val TAG = "PCSteuerung"
private const val DEFAULT_BROADCAST = "255.255.255.255"
private const val SEND_COUNT = 3
private const val SEND_DELAY_MS = 100L
