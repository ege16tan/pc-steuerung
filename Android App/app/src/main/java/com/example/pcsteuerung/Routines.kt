package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject

/**
 * Routinen des Agenten (Phase E, erweiterter Vertrag).
 *
 * Vertrag (Kurzform), wie ihn `PC Agent Rust/src/routines.rs` liefert:
 *  GET  /routines -> {"routines":[{
 *          "id":"feierabend","name":"Feierabend","enabled":true,
 *          "trigger":{"type":"time","at":"18:00","days":[1,2,3,4,5],
 *                     "event":"","idle_minutes":30,
 *                     "conditions":{"days":[],"plugin":"","power_plan":""}},
 *          "actions":[{"type":"power_plan","plan":"saver","delay":0,"wait":0},
 *                     {"type":"shutdown","delay":60,"wait":3}],
 *          "last_run":"2026-11-23 18:00","next_run":"2026-11-24 18:00",
 *          "history":[{"at":"2026-11-23 18:00","text":"Energieplan saver, ..."}]}]}
 *  POST /routines          <- ein Routine-Objekt (id/name/enabled/trigger/actions)
 *  DELETE /routines/{id}
 *  POST /routines/{id}/run
 *  GET  /routine-templates -> {"templates":[{"id","name","trigger","actions"}]}
 *
 * Gelesen wird bewusst tolerant: fehlende Felder werden zu Defaults, alte
 * Routinen ohne `conditions`/`history` bleiben lesbar, kaputtes JSON oder ein
 * HTTP-Fehler ergibt eine leere Liste. Ein Agent ohne `/routine-templates`
 * (404) liefert ebenfalls eine leere Liste - die Seite blendet den Abschnitt
 * "Vorlagen" dann einfach aus.
 *
 * Hinweis zu den Texten: die Kurztexte des Ausloesers ("Mo–Fr 18:00",
 * "Bei Download fertig", "Manuell") entstehen in [routineTriggerText]. Sie
 * stehen hier und nicht in `strings.xml`, weil sie eine reine Funktion ohne
 * Android-Abhaengigkeiten sind (Unit-Tests in `RoutinesTest.kt`).
 */

/** Ausloeser-Arten des Agenten. */
const val ROUTINE_TRIGGER_TIME = "time"
const val ROUTINE_TRIGGER_MANUAL = "manual"
const val ROUTINE_TRIGGER_EVENT = "event"

/** Ereignisse fuer `trigger.type = event`. */
const val ROUTINE_EVENT_GAME_STOPPED = "game_stopped"
const val ROUTINE_EVENT_DOWNLOAD_DONE = "download_done"
const val ROUTINE_EVENT_IDLE = "idle"

/** Alle Ereignisse, die die App anbietet. */
val ROUTINE_EVENTS = listOf(
    ROUTINE_EVENT_GAME_STOPPED,
    ROUTINE_EVENT_DOWNLOAD_DONE,
    ROUTINE_EVENT_IDLE,
)

/** Schritt-Arten des Agenten. */
const val ROUTINE_ACTION_SHUTDOWN = "shutdown"
const val ROUTINE_ACTION_HIBERNATE = "hibernate"
const val ROUTINE_ACTION_POWER_PLAN = "power_plan"

/** Reihenfolge der Schritte im Formular (Herunterfahren zuletzt). */
val ROUTINE_ACTION_TYPES = listOf(
    ROUTINE_ACTION_POWER_PLAN,
    ROUTINE_ACTION_HIBERNATE,
    ROUTINE_ACTION_SHUTDOWN,
)

/** Energieplaene, die der Agent kennt (wie `planLabel` in Core.kt). */
val ROUTINE_PLAN_KEYS = listOf("high", "balanced", "saver")

/** Wochentage 1 = Mo ... 7 = So (wie im Agenten). */
val ROUTINE_DAY_SHORT = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** 1-3 Schritte je Routine (Vertrag). */
const val MAX_ROUTINE_STEPS = 3

/** Mehr Verlauf zeigt die App nicht an. */
const val ROUTINE_HISTORY_LIMIT = 5

/** Wartezeit vor einem Schritt: 0-300 Sekunden (Vertrag). */
val ROUTINE_WAIT_RANGE = 0..300

/** Countdown fuer Herunterfahren: 0-3600 Sekunden. */
val ROUTINE_DELAY_RANGE = 0..3600

// ---------------------------------------------------------------------------
// Datenmodell
// ---------------------------------------------------------------------------

/** Bedingungen: Wochentage, laufendes Plugin, aktiver Energieplan (leer = egal). */
data class RoutineConditions(
    val days: List<Int> = emptyList(),
    val plugin: String = "",
    val powerPlan: String = "",
) {
    /** Keine Bedingung gesetzt - dann greift die Routine immer. */
    val isEmpty: Boolean get() = days.isEmpty() && plugin.isBlank() && powerPlan.isBlank()
}

/** Ausloeser einer Routine. */
data class RoutineTrigger(
    val type: String = ROUTINE_TRIGGER_MANUAL,
    val at: String = "",
    val days: List<Int> = emptyList(),
    val event: String = "",
    val idleMinutes: Int = 30,
    val conditions: RoutineConditions = RoutineConditions(),
)

/** Ein Schritt einer Routine. */
data class RoutineActionInfo(
    val type: String = "",
    val plan: String = "",
    val delay: Int = 0,
    val wait: Int = 0,
) {
    /** Kurztext fuer die Liste ("Ruhezustand", "Herunterfahren in 60s", ...). */
    val label: String
        get() {
            val base = when (type) {
                ROUTINE_ACTION_SHUTDOWN -> "Herunterfahren in ${delay}s"
                ROUTINE_ACTION_HIBERNATE -> "Ruhezustand"
                ROUTINE_ACTION_POWER_PLAN -> "Energieplan ${planLabel(plan)}"
                else -> type
            }
            return if (wait > 0) "$base (nach ${wait}s)" else base
        }
}

/** Ein Eintrag im Verlauf einer Routine. */
data class RoutineHistoryEntry(
    val at: String = "",
    val text: String = "",
)

/** Eine Routine des Agenten. */
data class RoutineInfo(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: RoutineTrigger = RoutineTrigger(),
    val actions: List<RoutineActionInfo> = emptyList(),
    val lastRun: String = "",
    val nextRun: String = "",
    val history: List<RoutineHistoryEntry> = emptyList(),
) {
    /** Kurztext des Ausloesers fuer Liste und Kachel. */
    val triggerText: String get() = routineTriggerText(trigger)

    val actionTexts: List<String> get() = actions.map { it.label }

    /** Die letzten Laeufe (hoechstens [ROUTINE_HISTORY_LIMIT]). */
    val recentHistory: List<RoutineHistoryEntry>
        get() = history.take(ROUTINE_HISTORY_LIMIT)

    // Kurzformen fuer bestehende Aufrufer (Kachel, alte Listen).
    val triggerType: String get() = trigger.type
    val triggerAt: String get() = trigger.at
    val triggerDays: List<Int> get() = trigger.days
}

/** Vorlage aus `GET /routine-templates`. */
data class RoutineTemplate(
    val id: String = "",
    val name: String = "",
    val trigger: RoutineTrigger = RoutineTrigger(),
    val actions: List<RoutineActionInfo> = emptyList(),
)

// ---------------------------------------------------------------------------
// JSON-Hilfen (tolerant, Datei-privat wie in Games.kt/Plugins.kt)
// ---------------------------------------------------------------------------

private fun JSONObject.routineText(name: String, fallback: String = ""): String {
    val value = optString(name, fallback).trim()
    return value.ifBlank { fallback }
}

/** Wochentage einer Liste: nur 1..7, ohne Doppelte, aufsteigend. */
private fun JSONArray.routineDays(): List<Int> =
    (0 until length())
        .map { index -> optInt(index, 0) }
        .filter { it in 1..7 }
        .distinct()
        .sorted()

private fun JSONObject.routineDays(name: String): List<Int> =
    optJSONArray(name)?.routineDays().orEmpty()

private fun JSONObject.routineConditions(): RoutineConditions {
    val json = optJSONObject("conditions") ?: return RoutineConditions()
    return RoutineConditions(
        days = json.routineDays("days"),
        plugin = json.routineText("plugin"),
        powerPlan = json.routineText("power_plan"),
    )
}

private fun JSONObject.toRoutineTrigger(): RoutineTrigger = RoutineTrigger(
    type = routineText("type", ROUTINE_TRIGGER_MANUAL),
    at = routineText("at"),
    days = routineDays("days"),
    event = routineText("event"),
    idleMinutes = optInt("idle_minutes", 0).coerceAtLeast(0),
    conditions = routineConditions(),
)

private fun JSONObject.toRoutineAction(): RoutineActionInfo = RoutineActionInfo(
    type = routineText("type"),
    plan = routineText("plan"),
    delay = optInt("delay", 0).coerceAtLeast(0),
    wait = optInt("wait", 0).coerceAtLeast(0),
)

private fun JSONArray.routineActions(): List<RoutineActionInfo> =
    (0 until length())
        .mapNotNull { index -> optJSONObject(index)?.toRoutineAction() }
        .filter { it.type.isNotBlank() }

private fun JSONArray.routineHistory(): List<RoutineHistoryEntry> =
    (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        val text = item.routineText("text")
        val at = item.routineText("at")
        // Zeilen ohne Inhalt sind kein Ereignis.
        if (text.isBlank() && at.isBlank()) null else RoutineHistoryEntry(at = at, text = text)
    }

private fun JSONObject.toRoutine(id: String): RoutineInfo = RoutineInfo(
    id = id,
    name = routineText("name", id),
    enabled = optBoolean("enabled", true),
    trigger = optJSONObject("trigger")?.toRoutineTrigger() ?: RoutineTrigger(),
    actions = optJSONArray("actions")?.routineActions().orEmpty(),
    lastRun = routineText("last_run"),
    nextRun = routineText("next_run"),
    history = optJSONArray("history")?.routineHistory().orEmpty(),
)

/**
 * Liste aus dem Vertragsobjekt (`{"routines":[...]}` bzw. `{"templates":[...]}`)
 * holen. Eine reine Liste `[...]` wird von den Parsern selbst behandelt.
 */
private fun routineArray(body: String, key: String): JSONArray {
    val text = body.trim()
    val json = JSONObject(text)
    return json.optJSONArray(key) ?: JSONArray()
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/**
 * Antwort von `GET /routines` auswerten. Tolerant: fehlende Felder werden zu
 * Defaults, Eintraege ohne `id` fallen weg (ohne Kennung laesst sich eine
 * Routine nicht bedienen), kaputtes JSON oder HTTP-Fehler ergibt eine leere
 * Liste.
 */
fun parseRoutines(body: String): List<RoutineInfo> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val text = body.trim()
        val array = if (text.startsWith("[")) JSONArray(text) else routineArray(text, "routines")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.routineText("id")
            if (id.isEmpty()) null else item.toRoutine(id)
        }
    }.getOrDefault(emptyList())
}

/**
 * Antwort von `GET /routine-templates` auswerten. Ein alter Agent ohne die
 * Route (HTTP 404) liefert eine leere Liste - die Seite blendet den
 * Vorlagen-Abschnitt dann aus, statt einen Fehler zu zeigen.
 */
fun parseRoutineTemplates(body: String): List<RoutineTemplate> {
    if (body.startsWith("HTTP ")) return emptyList()
    return runCatching {
        val text = body.trim()
        val array = if (text.startsWith("[")) JSONArray(text) else routineArray(text, "templates")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.routineText("id")
            if (id.isEmpty()) return@mapNotNull null
            RoutineTemplate(
                id = id,
                name = item.routineText("name", id),
                trigger = item.optJSONObject("trigger")?.toRoutineTrigger() ?: RoutineTrigger(),
                actions = item.optJSONArray("actions")?.routineActions().orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}

// ---------------------------------------------------------------------------
// Serialisierung fuer `POST /routines`
// ---------------------------------------------------------------------------

/** Ein Routine-Objekt als JSON (alle Felder des neuen Vertrags). */
fun routineJson(routine: RoutineInfo): String {
    val trigger = JSONObject()
        .put("type", routine.trigger.type)
        .put("at", routine.trigger.at)
        .put("days", JSONArray(routine.trigger.days))
        .put("event", routine.trigger.event)
        .put("idle_minutes", routine.trigger.idleMinutes)
        .put(
            "conditions",
            JSONObject()
                .put("days", JSONArray(routine.trigger.conditions.days))
                .put("plugin", routine.trigger.conditions.plugin)
                .put("power_plan", routine.trigger.conditions.powerPlan)
        )
    val actions = JSONArray()
    routine.actions.forEach { action ->
        actions.put(
            JSONObject()
                .put("type", action.type)
                .put("plan", action.plan)
                .put("delay", action.delay)
                .put("wait", action.wait)
        )
    }
    val json = JSONObject()
        .put("id", routine.id)
        .put("name", routine.name)
        .put("enabled", routine.enabled)
        .put("trigger", trigger)
        .put("actions", actions)
    // `next_run`/`history` gehoeren dem Agenten. Ein bereits bekanntes
    // `last_run` wird weiterhin mitgeschickt, damit ein alter Agent nichts
    // vergisst.
    if (routine.lastRun.isNotBlank()) json.put("last_run", routine.lastRun)
    return json.toString()
}

/** An/Aus umschalten - Kennung, Ausloeser und Schritte bleiben erhalten. */
fun withRoutineEnabled(routine: RoutineInfo, enabled: Boolean): RoutineInfo =
    routine.copy(enabled = enabled)

// ---------------------------------------------------------------------------
// Vorlagen
// ---------------------------------------------------------------------------

/** Vorlage in eine speicherbare Routine umwandeln (hoechstens 3 Schritte). */
fun routineFromTemplate(template: RoutineTemplate): RoutineInfo = RoutineInfo(
    id = template.id,
    name = template.name.ifBlank { template.id },
    enabled = true,
    trigger = template.trigger,
    actions = template.actions.take(MAX_ROUTINE_STEPS),
)

/**
 * Freie Kennung fuer eine neue Routine: [base], sonst "base-2", "base-3" ...
 * Damit ueberschreibt eine angelegte Vorlage keine vorhandene Routine.
 */
fun uniqueRoutineId(base: String, taken: Collection<String>): String {
    val id = base.trim().lowercase().ifBlank { "routine" }
    if (taken.none { it.equals(id, ignoreCase = true) }) return id
    var index = 2
    while (taken.any { it.equals("$id-$index", ignoreCase = true) }) index++
    return "$id-$index"
}

// ---------------------------------------------------------------------------
// Kurztexte (reine Funktionen, auch von der Kachel genutzt)
// ---------------------------------------------------------------------------

/** Wochentage als Kurztext: "" / "Täglich" / "Mo–Fr" / "Mo, Mi". */
fun routineDaysText(days: List<Int>): String {
    val clean = days.filter { it in 1..7 }.distinct().sorted()
    if (clean.isEmpty()) return ""
    if (clean.size == 7) return "Täglich"
    val names = clean.map { ROUTINE_DAY_SHORT[it - 1] }
    val consecutive = clean.size > 1 && clean.zipWithNext().all { (a, b) -> b == a + 1 }
    return if (consecutive) "${names.first()}–${names.last()}" else names.joinToString(", ")
}

/** Kurztext eines Ereignisses ("Bei Download fertig", bei inaktiv mit Minuten). */
fun routineEventText(event: String, idleMinutes: Int = 0): String = when (event) {
    ROUTINE_EVENT_GAME_STOPPED -> "Bei Spiel beendet"
    ROUTINE_EVENT_DOWNLOAD_DONE -> "Bei Download fertig"
    ROUTINE_EVENT_IDLE ->
        if (idleMinutes > 0) "Bei PC inaktiv ($idleMinutes min)" else "Bei PC inaktiv"
    else -> ""
}

/**
 * Kurztext des Ausloesers fuer Liste und Kachel:
 * "Mo–Fr 18:00", "Täglich 18:00", "Bei Download fertig", "Manuell".
 * Unbekannte Arten werden wie "Manuell" behandelt (tolerant).
 */
fun routineTriggerText(trigger: RoutineTrigger): String = when (trigger.type) {
    ROUTINE_TRIGGER_TIME -> {
        val days = routineDaysText(trigger.days)
        val at = trigger.at.trim()
        when {
            at.isEmpty() && days.isEmpty() -> "Täglich"
            at.isEmpty() -> days
            days.isEmpty() -> "Täglich $at"
            else -> "$days $at"
        }
    }

    ROUTINE_TRIGGER_EVENT ->
        routineEventText(trigger.event, trigger.idleMinutes).ifBlank { "Manuell" }

    else -> "Manuell"
}

/**
 * `next_run` fuer die Anzeige: getrimmt, leer wenn der Agent keinen Termin
 * kennt. Die Seite zeigt die Zeile dann gar nicht ("Nächster Lauf: " ohne
 * Datum waere nur Rauschen).
 */
fun routineNextRunText(nextRun: String): String = nextRun.trim()

/** Bedingungen als Kurztext ("Mo–Fr · game-optimizer · Energiesparen"). */
fun routineConditionsText(conditions: RoutineConditions): String {
    val parts = ArrayList<String>(3)
    val days = routineDaysText(conditions.days)
    if (days.isNotEmpty()) parts.add(days)
    if (conditions.plugin.isNotBlank()) parts.add(conditions.plugin)
    if (conditions.powerPlan.isNotBlank()) parts.add(planLabel(conditions.powerPlan))
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Formular: Entwurf, Umwandlung und Pruefung
// ---------------------------------------------------------------------------

/** Eingaben des Formulars als Text (der Nutzer tippt, der Agent bekommt Zahlen). */
data class RoutineDraft(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val triggerType: String = ROUTINE_TRIGGER_TIME,
    val at: String = "18:00",
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    val event: String = ROUTINE_EVENT_DOWNLOAD_DONE,
    val idleMinutes: String = "30",
    val conditionDays: List<Int> = emptyList(),
    val conditionPlugin: String = "",
    val conditionPowerPlan: String = "",
    val actions: List<RoutineActionDraft> = listOf(RoutineActionDraft()),
)

/** Eingaben eines Schritts. */
data class RoutineActionDraft(
    val type: String = ROUTINE_ACTION_POWER_PLAN,
    val plan: String = "saver",
    val delay: String = "60",
    val wait: String = "0",
)

/** Beanstandung vor dem Speichern (die Oberflaeche uebersetzt sie in Text). */
enum class RoutineValidationError {
    NAME_MISSING,
    TIME_INVALID,
    EVENT_MISSING,
    IDLE_INVALID,
    STEP_MISSING,
    TOO_MANY_STEPS,
    SHUTDOWN_NOT_LAST,
    PLAN_MISSING,
    WAIT_INVALID,
    DELAY_INVALID,
}

/** "18:00" bleibt "18:00"; "9:05" wird "09:05"; alles andere ergibt null. */
fun normalizeRoutineTime(value: String): String? {
    val match = Regex("^([0-9]{1,2}):([0-9]{2})$").find(value.trim()) ?: return null
    val hours = match.groupValues[1].toIntOrNull() ?: return null
    val minutes = match.groupValues[2].toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes)
}

/** Kennung aus dem Namen ("Feierabend" -> "feierabend", "Buero & Co" -> "buero-co"). */
fun routineIdFromName(name: String): String {
    val folded = name.trim().lowercase()
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss")
    val slug = folded.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
    return slug.replace(Regex("-+"), "-").trim('-')
}

/** Entwurf in eine Routine umwandeln (nur nach erfolgreicher Pruefung). */
fun routineFromDraft(draft: RoutineDraft): RoutineInfo {
    val type = draft.triggerType
    val at = if (type == ROUTINE_TRIGGER_TIME) normalizeRoutineTime(draft.at).orEmpty() else ""
    val idle = draft.idleMinutes.toIntOrNull()?.coerceIn(1, 600) ?: 30
    val trigger = RoutineTrigger(
        type = type,
        at = at,
        days = if (type == ROUTINE_TRIGGER_TIME) {
            draft.days.filter { it in 1..7 }.distinct().sorted()
        } else {
            emptyList()
        },
        event = if (type == ROUTINE_TRIGGER_EVENT) draft.event else "",
        idleMinutes = if (type == ROUTINE_TRIGGER_EVENT && draft.event == ROUTINE_EVENT_IDLE) {
            idle
        } else {
            0
        },
        conditions = RoutineConditions(
            days = draft.conditionDays.filter { it in 1..7 }.distinct().sorted(),
            plugin = draft.conditionPlugin.trim(),
            powerPlan = draft.conditionPowerPlan.trim(),
        ),
    )
    val actions = draft.actions.map { step ->
        RoutineActionInfo(
            type = step.type,
            plan = if (step.type == ROUTINE_ACTION_POWER_PLAN) step.plan.trim() else "",
            delay = if (step.type == ROUTINE_ACTION_SHUTDOWN) {
                (step.delay.toIntOrNull() ?: 0)
                    .coerceIn(ROUTINE_DELAY_RANGE.first, ROUTINE_DELAY_RANGE.last)
            } else {
                0
            },
            wait = (step.wait.toIntOrNull() ?: 0)
                .coerceIn(ROUTINE_WAIT_RANGE.first, ROUTINE_WAIT_RANGE.last),
        )
    }
    return RoutineInfo(
        id = draft.id.trim().ifBlank { routineIdFromName(draft.name) },
        name = draft.name.trim(),
        enabled = draft.enabled,
        trigger = trigger,
        actions = actions,
    )
}

/** Bestehende Routine als Entwurf fuer das Formular ("Bearbeiten"). */
fun routineToDraft(routine: RoutineInfo): RoutineDraft = RoutineDraft(
    id = routine.id,
    name = routine.name,
    enabled = routine.enabled,
    triggerType = routine.trigger.type,
    at = routine.trigger.at.ifBlank { "18:00" },
    days = routine.trigger.days,
    event = routine.trigger.event.ifBlank { ROUTINE_EVENT_DOWNLOAD_DONE },
    idleMinutes = routine.trigger.idleMinutes.takeIf { it > 0 }?.toString() ?: "30",
    conditionDays = routine.trigger.conditions.days,
    conditionPlugin = routine.trigger.conditions.plugin,
    conditionPowerPlan = routine.trigger.conditions.powerPlan,
    actions = routine.actions.take(MAX_ROUTINE_STEPS).map { action ->
        RoutineActionDraft(
            type = action.type.ifBlank { ROUTINE_ACTION_POWER_PLAN },
            plan = action.plan.ifBlank { "saver" },
            delay = action.delay.toString(),
            wait = action.wait.toString(),
        )
    }.ifEmpty { listOf(RoutineActionDraft()) },
)

/**
 * Pruefung vor dem Speichern. Liefert die erste Beanstandung oder null.
 *
 * Regeln: Name noetig, bei Zeit ein gueltiges HH:MM, bei Ereignis ein
 * bekanntes Ereignis (bei "PC inaktiv" 1-600 Minuten), 1-3 Schritte, je Schritt
 * Wartezeit 0-300 s, Energieplan nur mit bekanntem Plan, Herunterfahren nur als
 * letzter Schritt mit Countdown 0-3600 s.
 */
fun routineValidationError(draft: RoutineDraft): RoutineValidationError? {
    if (draft.name.isBlank()) return RoutineValidationError.NAME_MISSING
    when (draft.triggerType) {
        ROUTINE_TRIGGER_TIME ->
            if (normalizeRoutineTime(draft.at) == null) return RoutineValidationError.TIME_INVALID

        ROUTINE_TRIGGER_EVENT -> {
            if (draft.event !in ROUTINE_EVENTS) return RoutineValidationError.EVENT_MISSING
            if (draft.event == ROUTINE_EVENT_IDLE) {
                val idle = draft.idleMinutes.toIntOrNull() ?: 0
                if (idle !in 1..600) return RoutineValidationError.IDLE_INVALID
            }
        }

        else -> Unit
    }
    val steps = draft.actions
    if (steps.isEmpty()) return RoutineValidationError.STEP_MISSING
    if (steps.size > MAX_ROUTINE_STEPS) return RoutineValidationError.TOO_MANY_STEPS
    for ((index, step) in steps.withIndex()) {
        when (step.type) {
            ROUTINE_ACTION_POWER_PLAN ->
                if (step.plan !in ROUTINE_PLAN_KEYS) return RoutineValidationError.PLAN_MISSING

            ROUTINE_ACTION_SHUTDOWN -> {
                if (index != steps.lastIndex) return RoutineValidationError.SHUTDOWN_NOT_LAST
                val delay = step.delay.toIntOrNull() ?: return RoutineValidationError.DELAY_INVALID
                if (delay !in ROUTINE_DELAY_RANGE) return RoutineValidationError.DELAY_INVALID
            }
        }
        val wait = step.wait.toIntOrNull() ?: return RoutineValidationError.WAIT_INVALID
        if (wait !in ROUTINE_WAIT_RANGE) return RoutineValidationError.WAIT_INVALID
    }
    // Herunterfahren hoechstens einmal und nur am Ende.
    if (steps.count { it.type == ROUTINE_ACTION_SHUTDOWN } > 1) {
        return RoutineValidationError.SHUTDOWN_NOT_LAST
    }
    return null
}

/** Wochentag in der Auswahl umschalten (1-7, sortiert, ohne Doppelte). */
fun withRoutineDay(days: List<Int>, day: Int): List<Int> {
    if (day !in 1..7) return days.filter { it in 1..7 }.distinct().sorted()
    return if (day in days) days - day else (days + day).distinct().sorted()
}

/** Einen Schritt im Entwurf ersetzen (Auswahl von Art/Plan/Countdown/Wartezeit). */
fun updateRoutineStep(
    draft: RoutineDraft,
    index: Int,
    transform: (RoutineActionDraft) -> RoutineActionDraft,
): RoutineDraft {
    if (index !in draft.actions.indices) return draft
    val actions = draft.actions.mapIndexed { position, step ->
        if (position == index) transform(step) else step
    }
    return draft.copy(actions = actions)
}

/**
 * Neuen Schritt anhaengen (hoechstens [MAX_ROUTINE_STEPS]). Steht am Ende schon
 * "Herunterfahren", wird der neue Schritt davor eingefuegt - so bleibt
 * Herunterfahren automatisch der letzte Schritt.
 */
fun withNewRoutineStep(actions: List<RoutineActionDraft>): List<RoutineActionDraft> {
    if (actions.size >= MAX_ROUTINE_STEPS) return actions
    val fresh = RoutineActionDraft(type = ROUTINE_ACTION_POWER_PLAN)
    if (actions.lastOrNull()?.type != ROUTINE_ACTION_SHUTDOWN) return actions + fresh
    return actions.dropLast(1) + fresh + actions.last()
}
