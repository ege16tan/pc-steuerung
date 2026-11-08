package com.example.pcsteuerung

import java.util.Base64

/**
 * Ein PC-Profil: alle Verbindungs- und Zugangsdaten fuer einen Rechner.
 * Die App kann mehrere Profile verwalten und zwischen ihnen umschalten.
 */
data class Profile(
    val id: String,
    val name: String = "PC",
    val host: String = "",
    val port: String = "8000",
    val user: String = "",
    val pass: String = "",
    val mac: String = "",
    val broadcast: String = "255.255.255.255",
    val defaultDelay: Int = 60,
) {
    val base: String get() = "http://$host:$port"
    val isComplete: Boolean get() = host.isNotBlank() && isValidPort(port)

    /** Basic Auth, wenn Benutzername/Passwort gesetzt sind. */
    fun authHeader(): AuthHeader? =
        if (user.isNotBlank() && pass.isNotBlank()) {
            val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
            AuthHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw))
        } else {
            null
        }
}

data class AuthHeader(val name: String, val value: String)

enum class PcStatus { ONLINE, ERROR, OFFLINE, UNKNOWN }

data class AgentState(
    val text: String = "Verbinde ...",
    val status: PcStatus = PcStatus.UNKNOWN,
    val plan: String = "?",
    val pending: Int = 0,
    val simulate: Boolean? = null,
)
