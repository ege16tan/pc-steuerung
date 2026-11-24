package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject

/**
 * Warnungen vom Agenten (`GET /warnings`).
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
 * Alle Felder sind optional; fehlende Werte bleiben null.
 */
data class WarningRule(
    val id: String = "",
    val label: String = "",
    val metric: String = "",
    val op: String = "",
    val threshold: Double = 0.0,
    val durationS: Int = 0,
    val enabled: Boolean = false,
)

data class WarningAlert(
    val id: String = "",
    val label: String = "",
    val message: String = "",
    val value: Double? = null,
    val threshold: Double? = null,
    val op: String = "",
    val durationS: Int? = null,
    val sinceS: Int? = null,
    val since: String = "",
    val sinceEpoch: Double = 0.0,
)

data class WarningsStatus(
    val rules: List<WarningRule> = emptyList(),
    val active: List<WarningAlert> = emptyList(),
    val triggered: List<WarningAlert> = emptyList(),
    val checkedAt: String = "",
    val intervalS: Int = 30,
) {
    val isEmpty: Boolean
        get() = rules.isEmpty() && active.isEmpty() && triggered.isEmpty()
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    return value.takeUnless { it.isNaN() || it.isInfinite() }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name).takeIf { it != 0 }
}

private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return optBoolean(name)
}

private fun JSONObject.optBlock(name: String): JSONObject? = opt(name) as? JSONObject

fun parseWarnings(body: String): WarningsStatus {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return WarningsStatus()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return WarningsStatus()
    return runCatching { readWarnings(json) }.getOrDefault(WarningsStatus())
}

/** Liest nur die Warn-Alerts (active + triggered) aus dem JSON. */
fun parseWarningAlerts(body: String): List<WarningAlert> {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return emptyList()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    val active = readAlerts(json.opt("active") as? JSONArray)
    val triggered = readAlerts(json.opt("triggered") as? JSONArray)
    return active + triggered
}

/** Extrahiert die `sinceEpoch`-Werte aus einer Alert-Liste. */
fun warningEpochs(alerts: List<WarningAlert>): Set<Long> {
    return alerts.mapNotNull { alert ->
        alert.sinceEpoch.takeIf { it > 0 }?.toLong()
    }.toSet()
}

/** Findet neue Alerts, deren Epoch nicht in `seen` enthalten ist. */
fun newWarningAlerts(alerts: List<WarningAlert>, seen: Set<Long>): List<WarningAlert> {
    return alerts.filter { alert ->
        alert.sinceEpoch > 0 && alert.sinceEpoch.toLong() !in seen
    }
}

private fun readWarnings(json: JSONObject): WarningsStatus = WarningsStatus(
    rules = readRules(json.opt("rules") as? JSONArray),
    active = readAlerts(json.opt("active") as? JSONArray),
    triggered = readAlerts(json.opt("triggered") as? JSONArray),
    checkedAt = json.optString("checked_at").trim(),
    intervalS = json.optIntOrNull("interval_s") ?: 30,
)

private fun readRules(array: JSONArray?): List<WarningRule> {
    if (array == null) return emptyList()
    val out = ArrayList<WarningRule>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        out.add(
            WarningRule(
                id = item.optString("id").trim(),
                label = item.optString("label").trim(),
                metric = item.optString("metric").trim(),
                op = item.optString("op").trim(),
                threshold = item.optDoubleOrNull("threshold") ?: 0.0,
                durationS = item.optIntOrNull("duration_s") ?: 0,
                enabled = item.optBooleanOrNull("enabled") ?: false,
            )
        )
    }
    return out
}

private fun readAlerts(array: JSONArray?): List<WarningAlert> {
    if (array == null) return emptyList()
    val out = ArrayList<WarningAlert>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        out.add(
            WarningAlert(
                id = item.optString("id").trim(),
                label = item.optString("label").trim(),
                message = item.optString("message").trim().ifBlank { item.optString("label").trim() },
                value = item.optDoubleOrNull("value"),
                threshold = item.optDoubleOrNull("threshold"),
                op = item.optString("op").trim(),
                durationS = item.optIntOrNull("duration_s"),
                sinceS = item.optIntOrNull("since_s"),
                since = item.optString("since").trim(),
                sinceEpoch = item.optDoubleOrNull("since_epoch") ?: 0.0,
            )
        )
    }
    return out
}