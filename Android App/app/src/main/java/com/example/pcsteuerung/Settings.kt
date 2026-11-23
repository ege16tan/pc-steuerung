package com.example.pcsteuerung

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Phase G: Einstellungen durchsuchbar, je Bereich zuruecksetzbar, Export/Import
 * als JSON und Plugin-Einstellungen aus dem Manifest-Schema.
 *
 * Alles hier ist eine **reine Funktion** (kein Android-Zugriff), damit Suche,
 * Reset, Export/Import und die Schema-Pruefung ohne Geraet testbar bleiben
 * (`SettingsTest.kt`). Die Oberflaeche in `MainActivity.kt` ruft nur diese
 * Funktionen auf.
 */

// ---------------------------------------------------------------------------
// G1 - Suche in der Einstellungs-Uebersicht
// ---------------------------------------------------------------------------

/**
 * Ein Eintrag der Einstellungs-Uebersicht. [key] sagt der Oberflaeche, welche
 * Zeile gerendert wird; gesucht wird in [title], [subtitle] und [section].
 */
data class SettingsSearchEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val section: String,
)

/**
 * Ein Bereich der Uebersicht mit seinen Eintraegen. Der Bereichstitel ist die
 * Ueberschrift ("DARSTELLUNG", "STEUERUNG", ...) und bleibt in der Suche
 * sichtbar, solange mindestens ein Eintrag darin trifft.
 */
data class SettingsSearchSection(
    val title: String,
    val entries: List<SettingsSearchEntry>,
)

/** Suchbegriff normalisieren: aussen trimmen, ohne Gross-/Kleinschreibung. */
fun normalizeSettingsQuery(query: String): String = query.trim().lowercase(Locale.ROOT)

/**
 * Trifft [query] auf den Eintrag zu? Geprueft werden Titel, Untertitel und
 * Bereich; Gross-/Kleinschreibung ist egal und der Begriff wird getrimmt.
 * Ein leerer Begriff trifft immer (die ganze Liste bleibt sichtbar).
 */
fun matchesSettingsQuery(entry: SettingsSearchEntry, query: String): Boolean {
    val needle = normalizeSettingsQuery(query)
    if (needle.isEmpty()) return true
    return entry.title.lowercase(Locale.ROOT).contains(needle) ||
        entry.subtitle.lowercase(Locale.ROOT).contains(needle) ||
        entry.section.lowercase(Locale.ROOT).contains(needle)
}

/**
 * Bereiche filtern: nur Treffer bleiben uebrig, Bereiche ohne Treffer fallen
 * weg (dann ist auch die Ueberschrift nicht zu sehen). Ein leeres Ergebnis
 * heisst in der Oberflaeche "Keine Treffer."
 */
fun searchSettings(
    sections: List<SettingsSearchSection>,
    query: String,
): List<SettingsSearchSection> = sections.mapNotNull { section ->
    val hits = section.entries.filter { matchesSettingsQuery(it, query) }
    if (hits.isEmpty()) null else SettingsSearchSection(section.title, hits)
}

// ---------------------------------------------------------------------------
// G2 - Zuruecksetzen je Bereich
// ---------------------------------------------------------------------------

/**
 * Bereiche mit eigenem "Zuruecksetzen". Welche Felder dazugehoeren, steht in
 * [settingsAreaFields]; alles andere bleibt beim Reset unberuehrt.
 */
enum class SettingsArea { APPEARANCE, CONTROL, HISTORY, PLUGINS }

/** Felder eines Bereichs (Schluessel wie im Export, `settingsToJson`). */
fun settingsAreaFields(area: SettingsArea): List<String> = when (area) {
    SettingsArea.APPEARANCE -> listOf("theme_mode", "refresh_seconds")
    SettingsArea.CONTROL -> listOf("online_card")
    SettingsArea.HISTORY -> listOf("history_lines")
    SettingsArea.PLUGINS -> listOf("plugin_auto_close")
}

/**
 * Setzt **nur** die Felder von [area] auf die Standardwerte aus [AppSettings]
 * zurueck. Andere Bereiche und die Profile (die gar nicht hier liegen)
 * bleiben unveraendert.
 */
fun resetSettingsArea(current: AppSettings, area: SettingsArea): AppSettings {
    val defaults = AppSettings()
    return when (area) {
        SettingsArea.APPEARANCE -> current.copy(
            themeMode = defaults.themeMode,
            refreshSeconds = defaults.refreshSeconds,
        )

        SettingsArea.CONTROL -> current.copy(onlineCard = defaults.onlineCard)
        SettingsArea.HISTORY -> current.copy(historyLines = defaults.historyLines)
        SettingsArea.PLUGINS -> current.copy(pluginAutoClose = defaults.pluginAutoClose)
    }
}

// ---------------------------------------------------------------------------
// G3 - Export/Import der Einstellungen (JSON, ohne Geheimnisse)
// ---------------------------------------------------------------------------

/** Kennung der Exportdatei; ein fremdes `format` wird abgelehnt. */
const val SETTINGS_EXPORT_FORMAT = "pc-steuerung-settings"

/** Version des Formats; eine andere Version wird abgelehnt. */
const val SETTINGS_EXPORT_VERSION = 1

/**
 * Alle AppSettings als JSON. Die Schluessel entsprechen den SharedPreferences
 * (`theme_mode`, `refresh_seconds`, ...), damit ein Export lesbar bleibt.
 * Geheimnisse enthaelt AppSettings bewusst keine.
 */
fun settingsToJson(settings: AppSettings): JSONObject = JSONObject()
    .put("theme_mode", settings.themeMode.name.lowercase(Locale.ROOT))
    .put("refresh_seconds", settings.refreshSeconds)
    .put("update_install_method", settings.updateInstallMethod.name.lowercase(Locale.ROOT))
    .put("online_card", settings.onlineCard)
    .put("plugin_auto_close", settings.pluginAutoClose)
    .put("history_lines", settings.historyLines)

/**
 * Ein Profil im Export: nur Name, Host, Port und MAC - rein informativ.
 * Benutzername, Passwort, Passwort-Hash und Token kommen nie in die Datei.
 */
fun settingsExportProfile(profile: Profile): JSONObject = JSONObject()
    .put("name", profile.name)
    .put("host", profile.host)
    .put("port", profile.port)
    .put("mac", profile.mac)

/** Dateiname des Exports: `pc-steuerung-einstellungen-YYYY-MM-DD.json`. */
fun settingsExportFileName(date: LocalDate = LocalDate.now()): String =
    "pc-steuerung-einstellungen-" + date.toString() + ".json"

/**
 * Kompletter Inhalt der Exportdatei. [exportedAt] ist ein fertiger Zeitstempel
 * (die Oberflaeche setzt ihn), damit die Funktion ohne Uhr testbar bleibt.
 */
fun settingsExportJson(
    settings: AppSettings,
    modules: Set<String>,
    profiles: List<Profile>,
    exportedAt: String,
): String {
    val moduleArray = JSONArray()
    modules.sorted().forEach { moduleArray.put(it) }
    val profileArray = JSONArray()
    profiles.forEach { profileArray.put(settingsExportProfile(it)) }
    return JSONObject()
        .put("format", SETTINGS_EXPORT_FORMAT)
        .put("version", SETTINGS_EXPORT_VERSION)
        .put("exported_at", exportedAt)
        .put("settings", settingsToJson(settings))
        .put("modules", moduleArray)
        .put("profiles", profileArray)
        .toString(2)
}

/** Gruende, aus denen eine Importdatei abgelehnt wird. */
enum class SettingsImportError { INVALID_JSON, WRONG_FORMAT, WRONG_VERSION }

/**
 * Ergebnis des Imports. Uebernommen werden nur `settings` und `modules`;
 * Profile werden bewusst ignoriert ([SettingsImportResult.Ok.profilesIgnored]
 * sagt der Oberflaeche, ob die Datei welche enthielt).
 */
sealed interface SettingsImportResult {
    data class Ok(
        val settings: AppSettings,
        val modules: Set<String>,
        val profilesIgnored: Boolean,
    ) : SettingsImportResult

    data class Failed(val error: SettingsImportError) : SettingsImportResult
}

/** Einen JSON-Wert tolerant als Zahl lesen (Zahl oder Zahl als Text). */
internal fun jsonNumber(value: Any?): Double? = when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toDouble()
    is String -> value.trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

/** Einen JSON-Wert tolerant als Wahrheitswert lesen. */
internal fun jsonBoolean(value: Any?): Boolean? = when (value) {
    null, JSONObject.NULL -> null
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> when (value.trim().lowercase(Locale.ROOT)) {
        "true", "1", "ja", "an" -> true
        "false", "0", "nein", "aus" -> false
        else -> null
    }

    else -> null
}

/** JSON-Wert als Text (Zahlen ohne ueberfluessige Nachkommastellen). */
internal fun jsonScalarText(value: Any?): String = when {
    value == null || value === JSONObject.NULL -> ""
    value is Boolean -> if (value) "true" else "false"
    value is Double || value is Float -> {
        val number = (value as Number).toDouble()
        if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
    }

    value is Number -> value.toString()
    value is String -> value
    else -> value.toString()
}

/**
 * Importdatei tolerant lesen: unbekannte Felder werden ignoriert, fehlende
 * Felder ergeben die Standardwerte. Nur `format` und `version` sind Pflicht -
 * eine fremde Datei wird mit [SettingsImportResult.Failed] abgelehnt.
 */
fun parseSettingsImport(body: String): SettingsImportResult {
    val text = body.trim()
    if (text.isEmpty()) return SettingsImportResult.Failed(SettingsImportError.INVALID_JSON)
    val root = runCatching { JSONObject(text) }.getOrNull()
        ?: return SettingsImportResult.Failed(SettingsImportError.INVALID_JSON)

    val format = root.optString("format").trim()
    if (!format.equals(SETTINGS_EXPORT_FORMAT, ignoreCase = true)) {
        return SettingsImportResult.Failed(SettingsImportError.WRONG_FORMAT)
    }
    // Fehlende Version gilt als 1 (tolerant), alles andere ist eine Fremddatei.
    val version = jsonNumber(root.opt("version"))?.toInt() ?: SETTINGS_EXPORT_VERSION
    if (version != SETTINGS_EXPORT_VERSION) {
        return SettingsImportResult.Failed(SettingsImportError.WRONG_VERSION)
    }

    val raw = root.optJSONObject("settings") ?: JSONObject()
    val defaults = AppSettings()
    val refresh = jsonNumber(raw.opt("refresh_seconds"))?.toInt()
        ?.takeIf { it in AppSettingsStore.REFRESH_CHOICES }
        ?: defaults.refreshSeconds
    val settings = AppSettings(
        themeMode = raw.optString("theme_mode").trim()
            .takeIf { it.isNotEmpty() }
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name.equals(name, true) } }
            ?: defaults.themeMode,
        refreshSeconds = refresh,
        updateInstallMethod = raw.optString("update_install_method").trim()
            .takeIf { it.isNotEmpty() }
            ?.let { name -> UpdateInstallMethod.entries.firstOrNull { it.name.equals(name, true) } }
            ?: defaults.updateInstallMethod,
        onlineCard = jsonBoolean(raw.opt("online_card")) ?: defaults.onlineCard,
        pluginAutoClose = jsonBoolean(raw.opt("plugin_auto_close")) ?: defaults.pluginAutoClose,
        historyLines = jsonNumber(raw.opt("history_lines"))?.toInt()
            ?.let(::normalizeHistoryLines)
            ?: defaults.historyLines,
    )

    val modules = LinkedHashSet<String>()
    val moduleArray = root.optJSONArray("modules")
    if (moduleArray != null) {
        for (index in 0 until moduleArray.length()) {
            val id = jsonScalarText(moduleArray.opt(index)).trim()
            if (id.isNotEmpty()) modules.add(id)
        }
    }

    // `profiles` wird nie gelesen - dort stehen Name/Host/Port/MAC, die App
    // behaelt ihre eigenen Profile (samt Zugangsdaten) unveraendert.
    return SettingsImportResult.Ok(
        settings = settings,
        modules = modules,
        profilesIgnored = root.has("profiles"),
    )
}

// ---------------------------------------------------------------------------
// G4 - Plugin-Einstellungen aus dem Manifest-Schema (`/plugins/{id}/settings`)
// ---------------------------------------------------------------------------

/** Erlaubte Feldtypen des Schemas (`type` im Manifest). */
enum class PluginSettingType { TEXT, NUMBER, SWITCH, SELECT }

/** Ein Feld aus dem Schema; [defaultValue] ist immer Text (auch Zahlen). */
data class PluginSettingField(
    val key: String,
    val label: String,
    val type: PluginSettingType,
    val defaultValue: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val options: List<String> = emptyList(),
)

/**
 * Antwort von `GET /plugins/{id}/settings` in der Form, die die Oberflaeche
 * braucht: Schema und die aktuell gespeicherten Werte (als Text).
 */
data class PluginSettingsSchema(
    val fields: List<PluginSettingField>,
    val values: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = fields.isEmpty()
}

/**
 * `GET /plugins/{id}/settings` lesen. `null` bedeutet: keinen Abschnitt zeigen
 * (404, alter Agent, kaputtes JSON oder ein leeres Schema).
 */
fun parsePluginSettings(body: String): PluginSettingsSchema? {
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("HTTP ")) return null
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val array = root.optJSONArray("schema") ?: return null

    val fields = ArrayList<PluginSettingField>(array.length())
    val seen = HashSet<String>()
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        val key = obj.optString("key").trim()
        if (key.isEmpty() || !seen.add(key)) continue
        val type = when (obj.optString("type").trim().lowercase(Locale.ROOT)) {
            "text" -> PluginSettingType.TEXT
            "number" -> PluginSettingType.NUMBER
            "switch" -> PluginSettingType.SWITCH
            "select" -> PluginSettingType.SELECT
            else -> continue // unbekannter Typ: Feld auslassen (Agent prueft selbst)
        }
        val options = ArrayList<String>()
        val optionArray = obj.optJSONArray("options")
        if (optionArray != null) {
            for (optionIndex in 0 until optionArray.length()) {
                val option = jsonScalarText(optionArray.opt(optionIndex)).trim()
                if (option.isNotEmpty() && option !in options) options.add(option)
            }
        }
        fields.add(
            PluginSettingField(
                key = key,
                label = obj.optString("label").trim().ifEmpty { key },
                type = type,
                defaultValue = if (obj.has("default")) jsonScalarText(obj.opt("default")) else "",
                min = jsonNumber(obj.opt("min")),
                max = jsonNumber(obj.opt("max")),
                options = options,
            )
        )
    }
    if (fields.isEmpty()) return null

    val stored = LinkedHashMap<String, String>()
    val values = root.optJSONObject("values")
    if (values != null) {
        fields.forEach { field ->
            if (values.has(field.key)) stored[field.key] = jsonScalarText(values.opt(field.key))
        }
    }
    return PluginSettingsSchema(fields = fields, values = stored)
}

/** Startwerte fuer die Oberflaeche: gespeicherter Wert, sonst der Standardwert. */
fun pluginSettingValues(schema: PluginSettingsSchema): Map<String, String> =
    schema.fields.associate { field -> field.key to (schema.values[field.key] ?: field.defaultValue) }

/** Fehlerarten eines Zahlenfelds (deutsche Texte liegen in `strings.xml`). */
enum class PluginNumberError { EMPTY, NOT_A_NUMBER, TOO_SMALL, TOO_LARGE }

/**
 * Zahlenfeld pruefen: `null` = in Ordnung, sonst der Grund. Ein Komma gilt als
 * Dezimaltrennzeichen (deutsche Tastatur), Grenzen kommen aus min/max.
 */
fun checkPluginNumber(field: PluginSettingField, raw: String): PluginNumberError? {
    val text = raw.trim()
    if (text.isEmpty()) return PluginNumberError.EMPTY
    val value = text.replace(',', '.').toDoubleOrNull() ?: return PluginNumberError.NOT_A_NUMBER
    if (!value.isFinite()) return PluginNumberError.NOT_A_NUMBER
    field.min?.let { if (value < it) return PluginNumberError.TOO_SMALL }
    field.max?.let { if (value > it) return PluginNumberError.TOO_LARGE }
    return null
}

/**
 * Werte als Body fuer `POST /plugins/{id}/settings`: `{"values": {...}}` mit
 * echten JSON-Typen (Zahl als Zahl, Schalter als true/false, sonst Text).
 */
fun pluginSettingsBody(schema: PluginSettingsSchema, values: Map<String, String>): String {
    val out = JSONObject()
    schema.fields.forEach { field ->
        val raw = (values[field.key] ?: field.defaultValue).trim()
        out.put(
            field.key,
            when (field.type) {
                PluginSettingType.NUMBER ->
                    raw.replace(',', '.').toDoubleOrNull()
                        ?.let { if (it == it.toLong().toDouble()) it.toLong() else it }
                        ?: raw

                PluginSettingType.SWITCH -> jsonBoolean(raw) ?: false
                else -> raw
            },
        )
    }
    return JSONObject().put("values", out).toString()
}

/** Ergebnis von `POST /plugins/{id}/settings`. */
sealed interface PluginSettingsSave {
    /** Der Agent hat gespeichert und liefert den vollstaendigen Satz zurueck. */
    data class Saved(val values: Map<String, String>) : PluginSettingsSave

    /** 400/404/422 oder unlesbare Antwort; [detail] ist der Text des Agenten. */
    data class Rejected(val code: Int?, val detail: String) : PluginSettingsSave
}

/** Text aus einer Fehlerantwort (`{"detail": "..."}`) holen. */
private fun settingsErrorDetail(body: String): String =
    runCatching { JSONObject(body.trim()).optString("detail").trim() }.getOrDefault("")

/** Ein JSON-Objekt in eine Text-Tabelle umwandeln. */
private fun settingsScalarMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    obj.keys().forEach { key -> out[key] = jsonScalarText(obj.opt(key)) }
    return out
}

/**
 * Antwort von `POST /plugins/{id}/settings` lesen. `httpCall` liefert Fehler
 * als `HTTP <code>: <body>`, daraus werden Code und Detailtext geholt.
 */
fun parsePluginSettingsSave(body: String): PluginSettingsSave {
    val text = body.trim()
    if (text.startsWith("HTTP ")) {
        val rest = text.removePrefix("HTTP ")
        val code = rest.substringBefore(':').trim().toIntOrNull()
        return PluginSettingsSave.Rejected(code, settingsErrorDetail(rest.substringAfter(':', "")))
    }
    val root = runCatching { JSONObject(text) }.getOrNull()
        ?: return PluginSettingsSave.Rejected(null, "")
    if (!root.optBoolean("ok", false)) {
        return PluginSettingsSave.Rejected(null, root.optString("detail").trim())
    }
    return PluginSettingsSave.Saved(settingsScalarMap(root.optJSONObject("values")))
}




