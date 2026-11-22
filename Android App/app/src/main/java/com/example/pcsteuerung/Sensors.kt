package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Systemwerte vom Agenten (`GET /sensors`).
 *
 * Vertrag (Kurzform):
 * {"cpu":{"available":true,"percent":12.3,"cores":16},
 *  "ram":{"available":true,"used_mb":9011,"total_mb":32768,"percent":27.5},
 *  "gpu":{"available":true,"name":"...","percent":34.0,
 *         "memory_used_mb":2048,"memory_total_mb":8192},
 *  "network":{"available":true,"name":"Ethernet","rx_bps":12000.0,"tx_bps":3000.0},
 *  "disks":[{"name":"C:","total_gb":931.5,"free_gb":250.3,"percent":73.1}],
 *  "fans":{"available":false,"reason":"..."},
 *  "temperatures":{"available":false,"reason":"..."}}
 *
 * Jeder Block darf fehlen, `available` darf false sein und einzelne Werte
 * duerfen null sein. Es wird bewusst nichts geschaetzt: fehlt ein Wert, bleibt
 * er null und die Oberflaeche zeigt "Nicht verfuegbar" statt einer Fantasiezahl.
 */

/** Ein Block ohne Messwerte (Luefter, Temperaturen): nur Verfuegbarkeit + Grund. */
data class SensorGroup(
    val available: Boolean = false,
    val reason: String = "",
)

/** CPU-Auslastung in Prozent, optionale Kernzahl. */
data class SensorCpu(
    val available: Boolean = false,
    val percent: Double? = null,
    val cores: Int? = null,
    val reason: String = "",
)

/** Arbeitsspeicher in MB (belegt/gesamt) und in Prozent. */
data class SensorRam(
    val available: Boolean = false,
    val usedMb: Long? = null,
    val totalMb: Long? = null,
    val percent: Double? = null,
    val reason: String = "",
)

/** Grafikkarte: Name, Auslastung und Speicher in MB. */
data class SensorGpu(
    val available: Boolean = false,
    val name: String = "",
    val percent: Double? = null,
    val memoryUsedMb: Long? = null,
    val memoryTotalMb: Long? = null,
    val reason: String = "",
)

/** Netzwerk: Name der Schnittstelle und Durchsatz in Byte je Sekunde. */
data class SensorNetwork(
    val available: Boolean = false,
    val name: String = "",
    val rxBps: Double? = null,
    val txBps: Double? = null,
    val reason: String = "",
)

/** Ein Datentraeger (Laufwerksbuchstabe, Groesse in GB, Belegung in Prozent). */
data class SensorDisk(
    val name: String = "",
    val totalGb: Double? = null,
    val freeGb: Double? = null,
    val percent: Double? = null,
)

/** Gesamtantwort von `GET /sensors`. */
data class SensorsStatus(
    val cpu: SensorCpu = SensorCpu(),
    val ram: SensorRam = SensorRam(),
    val gpu: SensorGpu = SensorGpu(),
    val network: SensorNetwork = SensorNetwork(),
    val disks: List<SensorDisk> = emptyList(),
    val fans: SensorGroup = SensorGroup(),
    val temperatures: SensorGroup = SensorGroup(),
) {
    /**
     * true, wenn der Agent keinen einzigen Block meldet - typisch fuer einen
     * aelteren Agenten ohne `/sensors` (404). Dann zeigt die Seite nur den
     * Hinweis "Nicht verfuegbar" statt sieben leerer Kacheln.
     */
    val isEmpty: Boolean
        get() = !cpu.available && !ram.available && !gpu.available && !network.available &&
            disks.isEmpty() && !fans.available && !temperatures.available
}

// ---------------------------------------------------------------------------
// Parser: tolerant lesen (optJSON*/has/isNull), kaputte Antworten ergeben einen
// leeren Status statt einer Ausnahme.
// ---------------------------------------------------------------------------

/** JSON-Null, fehlende Felder und Text ergeben null (kein NaN, keine 0). */
private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    return value.takeUnless { it.isNaN() || it.isInfinite() }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return when (val raw = opt(name)) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}

/** Ein Block als JSON-Objekt; null, wenn er fehlt oder null ist. */
private fun JSONObject.optBlock(name: String): JSONObject? = opt(name) as? JSONObject

private fun JSONObject.optReason(): String = optString("reason").trim()

/** Prozentwerte immer auf 0..100 begrenzen (der Agent kann krumme Werte liefern). */
private fun percentOrNull(value: Double?): Double? = value?.coerceIn(0.0, 100.0)

/**
 * Wertet die Antwort von `GET /sensors` aus. Fehlt die Route (HTTP 404),
 * ist die Antwort leer oder unlesbar, kommt ein leerer Status zurueck
 * (alle Bloecke "nicht verfuegbar") - die App stuerzt nie ab.
 */
fun parseSensors(body: String): SensorsStatus {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return SensorsStatus()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return SensorsStatus()
    return runCatching { readSensors(json) }.getOrDefault(SensorsStatus())
}

private fun readSensors(json: JSONObject): SensorsStatus = SensorsStatus(
    cpu = readCpu(json.optBlock("cpu")),
    ram = readRam(json.optBlock("ram")),
    gpu = readGpu(json.optBlock("gpu")),
    network = readNetwork(json.optBlock("network")),
    disks = readDisks(json.opt("disks") as? JSONArray),
    fans = readGroup(json.optBlock("fans")),
    temperatures = readGroup(json.optBlock("temperatures")),
)

private fun readCpu(node: JSONObject?): SensorCpu {
    if (node == null) return SensorCpu()
    if (!node.optBoolean("available", false)) {
        return SensorCpu(available = false, reason = node.optReason())
    }
    return SensorCpu(
        available = true,
        percent = percentOrNull(node.optDoubleOrNull("percent")),
        cores = node.optLongOrNull("cores")?.toInt(),
        reason = node.optReason(),
    )
}

private fun readRam(node: JSONObject?): SensorRam {
    if (node == null) return SensorRam()
    if (!node.optBoolean("available", false)) {
        return SensorRam(available = false, reason = node.optReason())
    }
    return SensorRam(
        available = true,
        usedMb = node.optLongOrNull("used_mb"),
        totalMb = node.optLongOrNull("total_mb"),
        percent = percentOrNull(node.optDoubleOrNull("percent")),
        reason = node.optReason(),
    )
}

private fun readGpu(node: JSONObject?): SensorGpu {
    if (node == null) return SensorGpu()
    if (!node.optBoolean("available", false)) {
        return SensorGpu(available = false, reason = node.optReason())
    }
    return SensorGpu(
        available = true,
        name = node.optString("name").trim(),
        percent = percentOrNull(node.optDoubleOrNull("percent")),
        memoryUsedMb = node.optLongOrNull("memory_used_mb"),
        memoryTotalMb = node.optLongOrNull("memory_total_mb"),
        reason = node.optReason(),
    )
}

private fun readNetwork(node: JSONObject?): SensorNetwork {
    if (node == null) return SensorNetwork()
    if (!node.optBoolean("available", false)) {
        return SensorNetwork(available = false, reason = node.optReason())
    }
    return SensorNetwork(
        available = true,
        name = node.optString("name").trim(),
        rxBps = node.optDoubleOrNull("rx_bps")?.coerceAtLeast(0.0),
        txBps = node.optDoubleOrNull("tx_bps")?.coerceAtLeast(0.0),
        reason = node.optReason(),
    )
}

/** Datentraeger ohne Namen werden verworfen (dann lieber gar keine Zeile). */
private fun readDisks(array: JSONArray?): List<SensorDisk> {
    if (array == null) return emptyList()
    val out = ArrayList<SensorDisk>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val name = item.optString("name").trim()
        if (name.isBlank()) continue
        out.add(
            SensorDisk(
                name = name,
                totalGb = item.optDoubleOrNull("total_gb")?.coerceAtLeast(0.0),
                freeGb = item.optDoubleOrNull("free_gb")?.coerceAtLeast(0.0),
                percent = percentOrNull(item.optDoubleOrNull("percent")),
            )
        )
    }
    return out
}

private fun readGroup(node: JSONObject?): SensorGroup {
    if (node == null) return SensorGroup()
    return SensorGroup(
        available = node.optBoolean("available", false),
        reason = node.optReason(),
    )
}


// ---------------------------------------------------------------------------
// Formatierung: reine Funktionen mit deutscher Schreibweise (Komma, Punkt als
// Tausendertrenner) - dieselbe Machart wie fmtBytes/fmtPercent in Core.kt.
// Die Einheiten (%, GB, MB, Mbit/s) stehen als Ressourcen in strings.xml.
// ---------------------------------------------------------------------------

/** Prozentzahl ohne Nachkommastelle, begrenzt auf 0..100 ("12"). */
fun fmtSensorPercent(value: Double): String =
    String.format(Locale.GERMANY, "%.0f", value.coerceIn(0.0, 100.0))

/** Groesse in GB mit einer Nachkommastelle ("931,5"). */
fun fmtSensorGb(value: Double): String =
    String.format(Locale.GERMANY, "%.1f", value.coerceAtLeast(0.0))

/** Groesse in MB ohne Nachkommastelle, mit Tausenderpunkt ("9.011"). */
fun fmtSensorMb(value: Long): String =
    String.format(Locale.GERMANY, "%,d", value.coerceAtLeast(0L))

/** Durchsatz in Mbit/s ("0,1") - 8 Bit je Byte wie in der Netzwerk-Anzeige. */
fun fmtSensorRate(bytesPerSecond: Double): String {
    val mbit = bytesPerSecond.coerceAtLeast(0.0) * 8.0 / 1_000_000.0
    return String.format(Locale.GERMANY, "%.1f", mbit)
}

