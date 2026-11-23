package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ausgeloeste Warnung aus `GET /warnings` (Phase C).
 *
 * Vertrag (Kurzform):
 * {"rules":[{"id":"cpu-high","label":"CPU-Last","metric":"cpu_percent",
 *            "op":">","threshold":90.0,"duration_s":30,"enabled":true}],
 *  "active":[{"id":"cpu-high","label":"CPU-Last",
 *             "message":"CPU-Last: 91.4 % > 90.0 % seit 30 s",
 *             "value":91.4,"threshold":90.0,"op":">","duration_s":30,
 *             "since_s":30,"since":"2026-11-23 12:00:00","since_epoch":1763000000.0}],
 *  "triggered":[ ... gleiche Felder, neueste zuerst ... ],
 *  "checked_at":"2026-11-23 12:00:10","interval_s":30}
 *
 * Geprueft wird im Agenten (auch bei geschlossener App). Die App meldet jedes
 * Ereignis genau einmal; der Schluessel ist `since_epoch` (Unix-Sekunden).
 */
data class WarningAlert(
    val id: String,
    val label: String,
    val message: String,
    val epoch: Long,
)

/**
 * Wertet `GET /warnings` aus. Fehlt die Route (HTTP 404, alter Agent), ist die
 * Antwort leer oder unlesbar, kommt eine leere Liste zurueck - nie eine
 * Ausnahme, damit der Live-Dienst weiterlaeuft.
 */
fun parseWarningAlerts(body: String): List<WarningAlert> {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return emptyList()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    val array = json.opt("triggered") as? JSONArray ?: return emptyList()
    val out = ArrayList<WarningAlert>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val message = item.optString("message").trim()
            .ifBlank { item.optString("label").trim() }
        if (message.isBlank()) continue
        val epoch = item.optDouble("since_epoch", 0.0)
        // Ohne Zeitpunkt laesst sich das Ereignis nicht einmalig zuordnen.
        if (!epoch.isFinite() || epoch <= 0.0) continue
        out.add(
            WarningAlert(
                id = item.optString("id").trim(),
                label = item.optString("label").trim(),
                message = message,
                epoch = epoch.toLong(),
            )
        )
    }
    return out
}

/**
 * Ereignisse, die noch nicht gemeldet wurden (aeltester zuerst).
 *
 * [limit] begrenzt einen Nachlauf nach laengerer Pause: statt dreissig alter
 * Meldungen kommen nur die neuesten - der Rest wird still gemerkt.
 */
fun newWarningAlerts(
    alerts: List<WarningAlert>,
    seen: Set<Long>,
    limit: Int = 5,
): List<WarningAlert> =
    alerts
        .filter { it.epoch > 0 && it.epoch !in seen }
        .sortedBy { it.epoch }
        .takeLast(limit.coerceAtLeast(1))

/** Gemerkte Zeitpunkte, hoechstens [limit] (die neuesten). */
fun warningEpochs(alerts: List<WarningAlert>, limit: Int = 60): Set<Long> =
    alerts.map { it.epoch }.filter { it > 0 }.sorted().takeLast(limit.coerceAtLeast(1)).toSet()
