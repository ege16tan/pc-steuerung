package com.example.pcsteuerung

import org.json.JSONObject

/**
 * Reine Hilfsfunktionen ohne Android-Abhaengigkeiten. Dadurch lassen sie
 * sich mit einfachen JVM-Unit-Tests pruefen.
 */

fun isValidMac(mac: String): Boolean =
    mac.isBlank() || Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(mac.trim())

/** Gueltiger TCP-Port fuer die Agent-URL (1..65535). */
fun isValidPort(port: String): Boolean {
    val value = port.trim().toIntOrNull() ?: return false
    return value in 1..65535
}

fun fmtDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

fun planLabel(plan: String): String = when (plan) {
    "high" -> "Höchstleistung"
    "balanced" -> "Ausbalanciert"
    "saver" -> "Energiesparen"
    "anderer" -> "Anderer Plan"
    "unbekannt", "?" -> "Unbekannt"
    else -> plan
}

/**
 * Baut das 102-Byte Magic Packet (6 x 0xFF + 16 x MAC-Adresse).
 * Liefert null bei ungueltiger MAC-Adresse.
 */
fun buildMagicPacket(mac: String): ByteArray? {
    val macBytes = try {
        mac.trim().lowercase()
            .replace("-", ":")
            .split(":")
            .map { it.toInt(16).toByte() }
            .toByteArray()
    } catch (e: Exception) {
        return null
    }
    if (macBytes.size != 6) return null

    val packet = ByteArray(6 + 16 * 6)
    for (i in 0 until 6) packet[i] = 0xFF.toByte()
    for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
    return packet
}

/**
 * Wertet die Antwort der Status-Abfrage aus:
 * - HTTP-Fehler (401/403/...) -> ERROR mit passendem Hinweis
 * - sonst JSON mit power_plan / pending_shutdown / simulate -> ONLINE
 * - unlesbar -> ERROR
 */
fun parseStatus(body: String): AgentState = when {
    body.startsWith("HTTP 401") -> AgentState("Login fehlt oder falsch", PcStatus.ERROR, "?", 0)
    body.startsWith("HTTP 403") -> AgentState("Zugriff verweigert", PcStatus.ERROR, "?", 0)
    body.startsWith("HTTP ") -> AgentState(body, PcStatus.ERROR, "?", 0)
    else -> try {
        val json = JSONObject(body)
        val plan = json.optString("power_plan", "?")
        val pending = json.optInt("pending_shutdown", 0)
        val simulate = json.optBoolean("simulate", true)
        AgentState("Online · ${planLabel(plan)}", PcStatus.ONLINE, plan, pending, simulate)
    } catch (e: Exception) {
        AgentState("Antwort unlesbar", PcStatus.ERROR, "?", 0)
    }
}
