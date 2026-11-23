package com.example.pcsteuerung

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verlauf-Seite (Phase F1/F2/F3).
 *
 * Hier liegt alles, was der Verlauf berechnet und was sich ohne Android
 * pruefen laesst (Unit-Tests in `HistoryTest.kt`):
 *  - Ereignis-Modell [AgentEvent] samt Parser fuer `GET /events`
 *  - Kategorie je Ereignis (Filter-Chips "Alle/Steuerung/Routine/Plugin/Fehler")
 *  - Filter + Textsuche
 *  - Zeitgruppen "Heute/Gestern/Aelter"
 *  - Text fuer den Export in eine Datei
 *  - Aufbewahrung: Anzahl der geladenen Logzeilen (`history_lines`)
 *
 * Der Agent schreibt die Ereignisse in `PC Agent Rust/src/journal.rs` mit
 * `time = "%Y-%m-%d %H:%M:%S"` und Aktionen wie `shutdown`, `abort`,
 * `hibernate`, `power-plan`, `wake`, `mode`, `routine`, `plugin`, `warnung`.
 * Gelesen wird trotzdem tolerant: fehlende Felder, ein leerer Body oder ein
 * kaputter Zeitstempel duerfen die Seite nicht kippen.
 *
 * Hinweis zu Texten: die Zeilen fuer die Exportdatei entstehen hier und nicht
 * in `strings.xml`, weil dies reine Funktionen ohne Android-Abhaengigkeiten
 * sind (gleiche Begruendung wie in `Routines.kt`).
 */

// ---------------------------------------------------------------------------
// Ereignisse (`GET /events`)
// ---------------------------------------------------------------------------

/** Ein Ereignis aus `GET /events` (Ringpuffer, neueste zuerst). */
data class AgentEvent(
    val time: String = "",
    val action: String = "",
    val detail: String = "",
    val ok: Boolean = true,
)

/** `GET /events` auswerten. */
fun parseEvents(body: String): List<AgentEvent> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val array = JSONObject(body).optJSONArray("events") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            AgentEvent(
                time = item.optString("time"),
                action = item.optString("action"),
                detail = item.optString("detail"),
                ok = item.optBoolean("ok", true),
            )
        }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// F1 - Kategorie, Suche und Gruppen
// ---------------------------------------------------------------------------

/** Kategorie eines Ereignisses (ein Filter-Chip der Verlauf-Seite). */
enum class HistoryCategory {
    /** Kein Filter: alles anzeigen. */
    ALL,
    CONTROL,
    ROUTINE,
    PLUGIN,
    /** Fehlgeschlagene Aktion - hat Vorrang vor allen anderen Kategorien. */
    ERROR,

    /** Passt zu keinem Chip (z. B. `ping`, `setup`): nur unter "Alle". */
    OTHER,
}

/** Aktionen der Steuerung (`journal::log_event` im Agenten). */
val HISTORY_CONTROL_ACTIONS = setOf("shutdown", "abort", "hibernate", "power-plan", "wake", "mode")

/** Aktionen der Routinen. */
val HISTORY_ROUTINE_ACTIONS = setOf("routine")

/** Plugins, Spiele-Scan, Spiel-Konfiguration und Warnungen. */
val HISTORY_PLUGIN_ACTIONS = setOf("plugin", "games-scan", "game-config", "warnung")

/**
 * Kategorie eines Ereignisses. Fehler (`ok == false`) gewinnen immer: ein
 * fehlgeschlagener Shutdown bleibt ein Fehler und verschwindet nicht unter
 * "Steuerung".
 */
fun historyCategory(event: AgentEvent): HistoryCategory {
    if (!event.ok) return HistoryCategory.ERROR
    val action = event.action.trim().lowercase(Locale.ROOT)
    return when (action) {
        in HISTORY_CONTROL_ACTIONS -> HistoryCategory.CONTROL
        in HISTORY_ROUTINE_ACTIONS -> HistoryCategory.ROUTINE
        in HISTORY_PLUGIN_ACTIONS -> HistoryCategory.PLUGIN
        else -> HistoryCategory.OTHER
    }
}

/** Suchtext normalisieren: Leerzeichen aussen weg, Gross-/Kleinschreibung egal. */
fun normalizeHistoryQuery(query: String): String = query.trim().lowercase(Locale.ROOT)

/**
 * Filtert Ereignisse nach Kategorie und Suchtext (Aktion + Text). Die
 * Reihenfolge bleibt erhalten (neueste zuerst), es wird nichts umsortiert.
 * Leere Suche = kein Suchfilter.
 */
fun filterHistoryEvents(
    events: List<AgentEvent>,
    category: HistoryCategory = HistoryCategory.ALL,
    query: String = "",
): List<AgentEvent> {
    val needle = normalizeHistoryQuery(query)
    return events.filter { event ->
        if (category != HistoryCategory.ALL && historyCategory(event) != category) {
            return@filter false
        }
        if (needle.isEmpty()) return@filter true
        event.action.lowercase(Locale.ROOT).contains(needle) ||
            event.detail.lowercase(Locale.ROOT).contains(needle)
    }
}

/** Zeitgruppe der Verlauf-Seite. */
enum class HistoryGroup { TODAY, YESTERDAY, OLDER }

/** Eine Ueberschrift mit ihren Ereignissen (leere Gruppen entfallen). */
data class HistorySection(
    val group: HistoryGroup,
    val events: List<AgentEvent>,
)

/**
 * Datum aus dem Zeitstempel ("2026-11-23 14:05:00" -> 2026-11-23). Liefert
 * null, wenn der Zeitstempel fehlt oder unlesbar ist.
 */
fun parseHistoryDate(event: AgentEvent): LocalDate? {
    val time = event.time.trim()
    if (time.length < 10) return null
    return runCatching { LocalDate.parse(time.substring(0, 10)) }.getOrNull()
}

/**
 * Gruppe eines Ereignisses relativ zu [today]. Ohne (oder mit kaputtem)
 * Zeitstempel landet ein Ereignis unter "Aelter" - lieber alt einsortieren als
 * eine falsche Ueberschrift behaupten. Ein Datum in der Zukunft (Uhr des PCs
 * laeuft vor) gilt als "Heute".
 */
fun historyGroupOf(event: AgentEvent, today: LocalDate): HistoryGroup {
    val date = parseHistoryDate(event) ?: return HistoryGroup.OLDER
    return when {
        date.isBefore(today.minusDays(1)) -> HistoryGroup.OLDER
        date.isBefore(today) -> HistoryGroup.YESTERDAY
        else -> HistoryGroup.TODAY
    }
}

/**
 * Ereignisse in die Gruppen "Heute", "Gestern", "Aelter" aufteilen. Die
 * Reihenfolge der Gruppen ist fest, die Reihenfolge innerhalb einer Gruppe
 * bleibt wie uebergeben (neueste zuerst). Leere Gruppen entfallen.
 */
fun groupHistoryEvents(events: List<AgentEvent>, today: LocalDate): List<HistorySection> =
    HistoryGroup.entries.mapNotNull { group ->
        val matching = events.filter { historyGroupOf(it, today) == group }
        if (matching.isEmpty()) null else HistorySection(group, matching)
    }

/** Mehr Ereignisse zeigt die Verlauf-Seite nicht (lange Listen rendern sonst traege). */
const val HISTORY_EVENT_LIMIT = 50

// ---------------------------------------------------------------------------
// F2 - Export als Textdatei
// ---------------------------------------------------------------------------

/** Trenner zwischen den Feldern einer Exportzeile. */
const val HISTORY_EXPORT_SEPARATOR = " | "

/** Platzhalter fuer fehlende Zeit/Aktion in der Exportdatei. */
const val HISTORY_EXPORT_PLACEHOLDER = "-"

/** Kennzeichen fuer fehlgeschlagene Ereignisse (Liste und Exportdatei). */
const val HISTORY_ERROR_LABEL = "Fehler"

/** Dateiname des Exports, z. B. `verlauf-2026-11-23.txt`. */
fun historyExportFileName(date: LocalDate): String = "verlauf-$date.txt"

/** Zeitpunkt fuer die Kopfzeile, z. B. `23.11.2026 14:05`. */
fun historyExportTimestamp(now: LocalDateTime): String = String.format(
    Locale.GERMANY,
    "%02d.%02d.%04d %02d:%02d",
    now.dayOfMonth,
    now.monthValue,
    now.year,
    now.hour,
    now.minute,
)

/**
 * Eine Zeile der Exportdatei: Datum/Uhrzeit, Aktion, Text und - nur bei einem
 * Fehler - das Kennzeichen "Fehler". Leere Felder werden ausgelassen, damit
 * keine Zeilen mit Luecken entstehen.
 */
fun historyExportLine(event: AgentEvent): String {
    val fields = mutableListOf(
        event.time.trim().ifBlank { HISTORY_EXPORT_PLACEHOLDER },
        event.action.trim().ifBlank { HISTORY_EXPORT_PLACEHOLDER },
    )
    val detail = event.detail.trim().replace('\n', ' ').replace('\r', ' ').trim()
    if (detail.isNotEmpty()) fields += detail
    if (!event.ok) fields += HISTORY_ERROR_LABEL
    return fields.joinToString(HISTORY_EXPORT_SEPARATOR)
}

/**
 * Kompletter Inhalt des Exports: Kopfzeile mit PC-Name und Zeitpunkt, dann die
 * (bereits gefilterten) Zeilen wie in der Liste. Bei leerer Liste steht statt
 * der Zeilen ein Hinweis.
 */
fun historyExportText(
    pcName: String,
    exportedAt: String,
    events: List<AgentEvent>,
): String {
    val name = pcName.trim().ifBlank { "PC" }
    val stamp = exportedAt.trim().ifBlank { HISTORY_EXPORT_PLACEHOLDER }
    val lines = mutableListOf(
        "PC Steuerung - Verlauf",
        "PC: $name",
        "Exportiert: $stamp",
        "Einträge: ${events.size}",
        "",
    )
    if (events.isEmpty()) {
        lines += "Keine Einträge für diesen Filter."
    } else {
        events.forEach { lines += historyExportLine(it) }
    }
    return lines.joinToString("\n") + "\n"
}

// ---------------------------------------------------------------------------
// F3 - Aufbewahrung (`history_lines` in SharedPreferences)
// ---------------------------------------------------------------------------

/** Erlaubte Anzahlen geladener Logzeilen (Auswahl in den Einstellungen). */
val HISTORY_LINES_CHOICES = listOf(200, 500, 1000)

/** Standard, wenn nichts gespeichert ist oder der Wert nicht passt. */
const val HISTORY_LINES_DEFAULT = 200

/** Ungueltige oder fehlende Werte werden auf [HISTORY_LINES_DEFAULT] gezogen. */
fun normalizeHistoryLines(value: Int): Int =
    if (value in HISTORY_LINES_CHOICES) value else HISTORY_LINES_DEFAULT

/** Wert fuer SharedPreferences (`history_lines`). */
fun encodeHistoryLines(value: Int): String = normalizeHistoryLines(value).toString()

/** Gespeicherten Wert lesen: null, leer oder Unsinn ergibt den Standard. */
fun decodeHistoryLines(raw: String?): Int {
    val parsed = raw?.trim()?.toIntOrNull() ?: return HISTORY_LINES_DEFAULT
    return normalizeHistoryLines(parsed)
}
