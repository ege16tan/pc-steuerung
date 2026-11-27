package com.example.pcsteuerung

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase G: Suche, Reset je Bereich, Export/Import und die Plugin-Einstellungen
 * aus dem Manifest-Schema. Reine JVM-Tests ohne Android-Rahmen (wie
 * `WarningsTest`); die Logik liegt vollstaendig in `Settings.kt`.
 */
class SettingsTest {

    // -----------------------------------------------------------------------
    // G1 - Suche
    // -----------------------------------------------------------------------

    private fun entry(key: String, title: String, subtitle: String, section: String) =
        SettingsSearchEntry(key = key, title = title, subtitle = subtitle, section = section)

    private val sections = listOf(
        SettingsSearchSection(
            title = "DARSTELLUNG",
            entries = listOf(
                entry("appearance", "Darstellung", "System", "DARSTELLUNG"),
                entry("refresh", "Status-Aktualisierung", "Alle 5 Sekunden", "DARSTELLUNG"),
            ),
        ),
        SettingsSearchSection(
            title = "STEUERUNG",
            entries = listOf(
                entry("online_card", "Dauerkarte in der Statusleiste", "Aktiv", "STEUERUNG"),
            ),
        ),
        SettingsSearchSection(
            title = "VERLAUF",
            entries = listOf(
                entry("history", "Aufbewahrung im Verlauf", "200 Zeilen", "VERLAUF"),
            ),
        ),
    )

    @Test
    fun `Suche findet Treffer im Titel`() {
        val hits = searchSettings(sections, "Dauerkarte")
        assertEquals(1, hits.size)
        assertEquals("STEUERUNG", hits[0].title)
        assertEquals(listOf("online_card"), hits[0].entries.map { it.key })
    }

    @Test
    fun `Suche findet Treffer im Untertitel`() {
        val hits = searchSettings(sections, "200 Zeilen")
        assertEquals(1, hits.size)
        assertEquals("VERLAUF", hits[0].title)
        assertEquals(listOf("history"), hits[0].entries.map { it.key })
    }

    @Test
    fun `Suche findet Treffer im Bereich und behaelt dessen Ueberschrift`() {
        val hits = searchSettings(sections, "steuerung")
        assertEquals(1, hits.size)
        // Beide Eintraege des Bereichs treffen, die Ueberschrift bleibt sichtbar.
        assertEquals("STEUERUNG", hits[0].title)
        assertEquals(listOf("online_card"), hits[0].entries.map { it.key })
    }

    @Test
    fun `Suche ignoriert Gross- und Kleinschreibung`() {
        val hits = searchSettings(sections, "dAuErKaRtE")
        assertEquals(listOf("online_card"), hits[0].entries.map { it.key })
        // Auch der Bereich selbst ist unabhaengig von der Schreibweise: der
        // Begriff trifft beide Eintraege ueber den Bereichsnamen.
        val appearance = searchSettings(sections, "darstellung")
        assertEquals(1, appearance.size)
        assertEquals("DARSTELLUNG", appearance[0].title)
        assertEquals(listOf("appearance", "refresh"), appearance[0].entries.map { it.key })
    }

    @Test
    fun `Suche trimmt den Begriff`() {
        val hits = searchSettings(sections, "  Dauerkarte  ")
        assertEquals(1, hits.size)
        assertEquals(listOf("online_card"), hits[0].entries.map { it.key })
    }

    @Test
    fun `Leere Suche zeigt alle Bereiche und Eintraege`() {
        val all = searchSettings(sections, "")
        assertEquals(sections.map { it.title }, all.map { it.title })
        assertEquals(3, all.size)
        assertEquals(4, all.sumOf { it.entries.size })
        // Nur Leerzeichen zaehlt wie eine leere Suche.
        assertEquals(4, searchSettings(sections, "   ").sumOf { it.entries.size })
    }

    @Test
    fun `Suche ohne Treffer liefert eine leere Liste`() {
        assertTrue(searchSettings(sections, "Zebrastreifen").isEmpty())
        // Normalisierung: der Begriff wird fuer den Vergleich geglaettet.
        assertEquals("dauerkarte", normalizeSettingsQuery("  Dauerkarte "))
    }

    // -----------------------------------------------------------------------
    // G2 - Zuruecksetzen je Bereich
    // -----------------------------------------------------------------------

    /** Alle Felder bewusst vom Standard weg - so faellt jede Aenderung auf. */
    private val changedSettings = AppSettings(
        themeMode = ThemeMode.DARK,
        refreshSeconds = 30,
        updateInstallMethod = UpdateInstallMethod.SHIZUKU,
        onlineCard = false,
        pluginAutoClose = mapOf("game-optimizer" to true, "system-monitor" to false),
        historyLines = 1000,
    )

    @Test
    fun `Reset Darstellung setzt nur Darstellung zurueck`() {
        val reset = resetSettingsArea(changedSettings, SettingsArea.APPEARANCE)
        assertEquals(ThemeMode.SYSTEM, reset.themeMode)
        assertEquals(5, reset.refreshSeconds)
        // Alles andere bleibt, wie es war.
        assertEquals(UpdateInstallMethod.SHIZUKU, reset.updateInstallMethod)
        assertFalse(reset.onlineCard)
        assertEquals(mapOf("game-optimizer" to true, "system-monitor" to false), reset.pluginAutoClose)
        assertEquals(1000, reset.historyLines)
    }

    @Test
    fun `Reset Steuerung setzt nur die Steuerung zurueck`() {
        val reset = resetSettingsArea(changedSettings, SettingsArea.CONTROL)
        assertTrue(reset.onlineCard)
        assertEquals(ThemeMode.DARK, reset.themeMode)
        assertEquals(30, reset.refreshSeconds)
        assertEquals(UpdateInstallMethod.SHIZUKU, reset.updateInstallMethod)
        assertEquals(mapOf("game-optimizer" to true, "system-monitor" to false), reset.pluginAutoClose)
        assertEquals(1000, reset.historyLines)
    }

    @Test
    fun `Reset Verlauf setzt nur die Verlaufsfelder zurueck`() {
        val reset = resetSettingsArea(changedSettings, SettingsArea.HISTORY)
        assertEquals(200, reset.historyLines)
        assertEquals(ThemeMode.DARK, reset.themeMode)
        assertEquals(30, reset.refreshSeconds)
        assertEquals(UpdateInstallMethod.SHIZUKU, reset.updateInstallMethod)
        assertFalse(reset.onlineCard)
        assertEquals(mapOf("game-optimizer" to true, "system-monitor" to false), reset.pluginAutoClose)
    }

@Test
    fun `Reset Plugins setzt nur die Plugin-Einstellung zurueck`() {
        val reset = resetSettingsArea(changedSettings, SettingsArea.PLUGINS)
        assertEquals(emptyMap<String, Boolean>(), reset.pluginAutoClose)
        assertEquals(ThemeMode.DARK, reset.themeMode)
        assertEquals(30, reset.refreshSeconds)
        assertEquals(UpdateInstallMethod.SHIZUKU, reset.updateInstallMethod)
        assertFalse(reset.onlineCard)
        assertEquals(1000, reset.historyLines)
    }

    @Test
    fun `Reset aller Bereiche ergibt die Standardwerte`() {
        var settings = changedSettings
        SettingsArea.entries.forEach { area ->
            settings = resetSettingsArea(settings, area)
        }
        // update_install_method gehoert zu keinem Bereich und bleibt deshalb stehen.
        assertEquals(AppSettings(updateInstallMethod = UpdateInstallMethod.SHIZUKU), settings)
        // Jeder Bereich nennt genau seine Felder.
        assertEquals(
            listOf("theme_mode", "refresh_seconds", "accent_color", "tile_columns"),
            settingsAreaFields(SettingsArea.APPEARANCE),
        )
        assertEquals(listOf("online_card"), settingsAreaFields(SettingsArea.CONTROL))
        assertEquals(listOf("history_lines"), settingsAreaFields(SettingsArea.HISTORY))
        assertEquals(listOf("plugin_auto_close"), settingsAreaFields(SettingsArea.PLUGINS))
        assertEquals(
            listOf("live_downloads", "live_countdown", "live_warnings", "live_starting"),
            settingsAreaFields(SettingsArea.LIVE_CARD),
        )
    }

    // -----------------------------------------------------------------------
    // G3 - Export
    // -----------------------------------------------------------------------

    /** Profil mit Zugangsdaten - davon darf nichts in der Datei landen. */
    private val profile = Profile(
        id = "profil-1",
        name = "Wohnzimmer-PC",
        host = "192.168.1.50",
        port = "8080",
        user = "geheimnutzer",
        pass = "streng-geheim",
        mac = "AA:BB:CC:DD:EE:FF",
        broadcast = "192.168.1.255",
        defaultDelay = 30,
    )

    @Test
    fun `Export schreibt Format Version und alle Einstellungen`() {
        val json = JSONObject(
            settingsExportJson(
                settings = changedSettings,
                modules = setOf("timer", "log"),
                profiles = listOf(profile),
                exportedAt = "2026-11-24 10:00:00",
            )
        )
        assertEquals("pc-steuerung-settings", json.getString("format"))
        assertEquals(SETTINGS_EXPORT_FORMAT, json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(SETTINGS_EXPORT_VERSION, json.getInt("version"))
        assertEquals("2026-11-24 10:00:00", json.getString("exported_at"))

        val settings = json.getJSONObject("settings")
        assertEquals("dark", settings.getString("theme_mode"))
        assertEquals(30, settings.getInt("refresh_seconds"))
        assertEquals("shizuku", settings.getString("update_install_method"))
        assertFalse(settings.getBoolean("online_card"))
        val pluginAutoCloseObj = settings.getJSONObject("plugin_auto_close")
        assertTrue(pluginAutoCloseObj.getBoolean("game-optimizer"))
        assertFalse(pluginAutoCloseObj.getBoolean("system-monitor"))
        assertEquals(1000, settings.getInt("history_lines"))

        val modules = json.getJSONArray("modules")
        assertEquals(listOf("log", "timer"), (0 until modules.length()).map { modules.getString(it) })
    }

    @Test
    fun `Export enthaelt keine Zugangsdaten`() {
        val text = settingsExportJson(
            settings = changedSettings,
            modules = emptySet(),
            profiles = listOf(profile),
            exportedAt = "2026-11-24 10:00:00",
        )
        // Benutzername, Passwort, Broadcast und Verzoegerung bleiben draussen.
        assertFalse(text.contains("geheimnutzer"))
        assertFalse(text.contains("streng-geheim"))
        assertFalse(text.contains("192.168.1.255"))
        assertFalse(text.contains("defaultDelay"))
        // Profil nur informativ: Name, Host, Port, MAC - sonst nichts.
        val json = JSONObject(text)
        val exported = json.getJSONArray("profiles").getJSONObject(0)
        assertEquals(setOf("name", "host", "port", "mac"), exported.keys().asSequence().toSet())
        assertEquals("Wohnzimmer-PC", exported.getString("name"))
        assertEquals("192.168.1.50", exported.getString("host"))
        assertEquals("8080", exported.getString("port"))
        assertEquals("AA:BB:CC:DD:EE:FF", exported.getString("mac"))
        // Die Wurzel hat genau die vereinbarten Felder (kein Platz fuer Token).
        assertEquals(
            setOf("format", "version", "exported_at", "settings", "modules", "profiles"),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `Export-Dateiname hat das Datum im Namen`() {
        assertEquals(
            "pc-steuerung-einstellungen-2026-11-24.json",
            settingsExportFileName(java.time.LocalDate.of(2026, 11, 24)),
        )
    }

    // -----------------------------------------------------------------------
    // G3 - Import
    // -----------------------------------------------------------------------

    private fun exportOf(
        settings: AppSettings = changedSettings,
        modules: Set<String> = setOf("log"),
        profiles: List<Profile> = listOf(profile),
    ): String = settingsExportJson(settings, modules, profiles, "2026-11-24 10:00:00")

    @Test
    fun `Import uebernimmt Einstellungen und Module`() {
        val result = parseSettingsImport(exportOf())
        assertTrue(result is SettingsImportResult.Ok)
        val ok = result as SettingsImportResult.Ok
        assertEquals(changedSettings, ok.settings)
        assertEquals(setOf("log"), ok.modules)
        assertTrue(ok.profilesIgnored)
    }

    @Test
    fun `Import ignoriert unbekannte Felder`() {
        val body = """
            {
              "format": "pc-steuerung-settings",
              "version": 1,
              "irgendwas": "egal",
              "settings": {
                "theme_mode": "light",
                "refresh_seconds": 10,
                "zukunft": true,
                "verschachtelt": {"a": 1}
              },
              "modules": ["timer", "unbekannt"]
            }
        """.trimIndent()
        val ok = parseSettingsImport(body) as SettingsImportResult.Ok
        assertEquals(ThemeMode.LIGHT, ok.settings.themeMode)
        assertEquals(10, ok.settings.refreshSeconds)
        // Nicht genannt = Standardwert, unbekannte Module bleiben erhalten.
        assertEquals(UpdateInstallMethod.SYSTEM, ok.settings.updateInstallMethod)
        assertEquals(setOf("timer", "unbekannt"), ok.modules)
        assertFalse(ok.profilesIgnored)
    }

    @Test
    fun `Import ohne Felder ergibt die Standardwerte`() {
        val body = """{"format":"pc-steuerung-settings","version":1}"""
        val ok = parseSettingsImport(body) as SettingsImportResult.Ok
        assertEquals(AppSettings(), ok.settings)
        assertTrue(ok.modules.isEmpty())
        assertFalse(ok.profilesIgnored)
    }

    @Test
    fun `Import zieht ungueltige Werte auf die Standardwerte`() {
        val body = """
            {
              "format": "pc-steuerung-settings",
              "version": 1,
              "settings": {
                "theme_mode": "neon",
                "refresh_seconds": 7,
                "online_card": "quatsch",
                "history_lines": 200.0
              }
            }
        """.trimIndent()
        val ok = parseSettingsImport(body) as SettingsImportResult.Ok
        assertEquals(ThemeMode.SYSTEM, ok.settings.themeMode)
        assertEquals(5, ok.settings.refreshSeconds)
        assertTrue(ok.settings.onlineCard)
        assertEquals(200, ok.settings.historyLines)
    }

    @Test
    fun `Import lehnt fremden Inhalt ab`() {
        assertEquals(
            SettingsImportResult.Failed(SettingsImportError.INVALID_JSON),
            parseSettingsImport(""),
        )
        assertEquals(
            SettingsImportResult.Failed(SettingsImportError.INVALID_JSON),
            parseSettingsImport("{kaputt"),
        )
        assertEquals(
            SettingsImportResult.Failed(SettingsImportError.WRONG_FORMAT),
            parseSettingsImport("""{"format":"etwas-anderes","version":1}"""),
        )
        assertEquals(
            SettingsImportResult.Failed(SettingsImportError.WRONG_FORMAT),
            parseSettingsImport("""{"version":1,"settings":{}}"""),
        )
        assertEquals(
            SettingsImportResult.Failed(SettingsImportError.WRONG_VERSION),
            parseSettingsImport("""{"format":"pc-steuerung-settings","version":2}"""),
        )
    }

    // -----------------------------------------------------------------------
    // G4 - Plugin-Einstellungen aus dem Schema
    // -----------------------------------------------------------------------

    private val schemaBody = """
        {
          "id": "game-optimizer",
          "schema": [
            {"key": "refresh_s", "label": "Aktualisierung (s)", "type": "number",
             "default": 5, "min": 1, "max": 600},
            {"key": "mode", "label": "Modus", "type": "select",
             "default": "auto", "options": ["auto", "manuell"]},
            {"key": "notify", "label": "Benachrichtigen", "type": "switch", "default": true},
            {"key": "note", "label": "Notiz", "type": "text", "default": ""}
          ],
          "values": {"refresh_s": 15, "mode": "manuell"}
        }
    """.trimIndent()

    @Test
    fun `Plugin-Schema liest Typen Grenzen Optionen und Werte`() {
        val schema = parsePluginSettings(schemaBody)!!
        assertFalse(schema.isEmpty)
        assertEquals(
            listOf("refresh_s", "mode", "notify", "note"),
            schema.fields.map { it.key },
        )
        assertEquals(
            listOf(
                PluginSettingType.NUMBER,
                PluginSettingType.SELECT,
                PluginSettingType.SWITCH,
                PluginSettingType.TEXT,
            ),
            schema.fields.map { it.type },
        )
        val number = schema.fields[0]
        assertEquals("Aktualisierung (s)", number.label)
        assertEquals(1.0, number.min!!, 0.0)
        assertEquals(600.0, number.max!!, 0.0)
        assertEquals("5", number.defaultValue)
        assertEquals(listOf("auto", "manuell"), schema.fields[1].options)
        assertEquals("auto", schema.fields[1].defaultValue)
        assertEquals("true", schema.fields[2].defaultValue)
        // Werte aus der Antwort, fehlende Felder bleiben aussen vor.
        assertEquals(mapOf("refresh_s" to "15", "mode" to "manuell"), schema.values)
    }

    @Test
    fun `Plugin-Werte nehmen den Standardwert, wenn nichts gespeichert ist`() {
        val values = pluginSettingValues(parsePluginSettings(schemaBody)!!)
        assertEquals("15", values["refresh_s"])
        assertEquals("manuell", values["mode"])
        assertEquals("true", values["notify"])
        assertEquals("", values["note"])
    }

    @Test
    fun `Ohne Schema bleibt der Abschnitt aus`() {
        assertNull(parsePluginSettings(""))
        assertNull(parsePluginSettings("HTTP 404: {\"detail\":\"Not Found\"}"))
        assertNull(parsePluginSettings("{\"schema\": []}"))
        assertNull(parsePluginSettings("{kaputt"))
        // Unbekannter Typ wird uebersprungen, ohne das ganze Schema zu kippen.
        val schema = parsePluginSettings(
            """{"schema":[{"key":"farbe","label":"Farbe","type":"color"},
                        {"key":"note","label":"Notiz","type":"text"}]}"""
        )!!
        assertEquals(listOf("note"), schema.fields.map { it.key })
    }

    @Test
    fun `Zahlenfeld prueft Grenzen und Unsinn`() {
        val field = PluginSettingField(
            key = "refresh_s",
            label = "Aktualisierung (s)",
            type = PluginSettingType.NUMBER,
            defaultValue = "5",
            min = 1.0,
            max = 600.0,
        )
        assertNull(checkPluginNumber(field, "5"))
        assertNull(checkPluginNumber(field, " 1 "))
        assertNull(checkPluginNumber(field, "600"))
        assertNull(checkPluginNumber(field, "12,5"))
        assertEquals(PluginNumberError.TOO_SMALL, checkPluginNumber(field, "0"))
        assertEquals(PluginNumberError.TOO_SMALL, checkPluginNumber(field, "0,5"))
        assertEquals(PluginNumberError.TOO_LARGE, checkPluginNumber(field, "601"))
        assertEquals(PluginNumberError.NOT_A_NUMBER, checkPluginNumber(field, "abc"))
        assertEquals(PluginNumberError.EMPTY, checkPluginNumber(field, "   "))
        // Ohne Grenzen ist jede Zahl in Ordnung.
        val open = field.copy(min = null, max = null)
        assertNull(checkPluginNumber(open, "999999"))
    }

    @Test
    fun `Plugin-Speichern schickt echte JSON-Typen`() {
        val schema = parsePluginSettings(schemaBody)!!
        val body = pluginSettingsBody(
            schema,
            mapOf("refresh_s" to "15", "mode" to "manuell", "notify" to "true", "note" to "Test"),
        )
        val values = JSONObject(body).getJSONObject("values")
        assertEquals(15, values.getInt("refresh_s"))
        assertEquals("manuell", values.getString("mode"))
        assertTrue(values.getBoolean("notify"))
        assertEquals("Test", values.getString("note"))
    }

    @Test
    fun `Antwort des Speicherns wird gelesen`() {
        val saved = parsePluginSettingsSave(
            """{"ok": true, "values": {"refresh_s": 15, "notify": true, "mode": "manuell"}}"""
        )
        assertEquals(
            PluginSettingsSave.Saved(
                mapOf("refresh_s" to "15", "notify" to "true", "mode" to "manuell")
            ),
            saved,
        )
        // Fehlerantworten behalten Code und deutschen Text des Agenten.
        assertEquals(
            PluginSettingsSave.Rejected(422, "Wert fuer 'Modus' (mode) ist ungueltig"),
            parsePluginSettingsSave(
                """HTTP 422: {"detail":"Wert fuer 'Modus' (mode) ist ungueltig"}"""
            ),
        )
        assertEquals(
            PluginSettingsSave.Rejected(400, "Unbekannter Einstellungsschluessel 'x'"),
            parsePluginSettingsSave(
                """HTTP 400: {"detail":"Unbekannter Einstellungsschluessel 'x'"}"""
            ),
        )
        assertEquals(PluginSettingsSave.Rejected(null, ""), parsePluginSettingsSave("kaputt"))
    }
}



