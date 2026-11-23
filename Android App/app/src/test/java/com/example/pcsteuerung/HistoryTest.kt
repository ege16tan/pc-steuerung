package com.example.pcsteuerung

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefungen fuer die Verlauf-Seite (Phase F): Kategorie-Zuordnung, Filter und
 * Suche, Zeitgruppen, Export-Text und die Einstellung `history_lines`.
 */
class HistoryTest {

    private fun event(
        action: String,
        detail: String = "",
        ok: Boolean = true,
        time: String = "2026-11-23 14:05:00",
    ) = AgentEvent(time = time, action = action, detail = detail, ok = ok)

    // ---------------- F1: Kategorie ----------------

    @Test
    fun `historyCategory ordnet Steuerungsaktionen zu`() {
        listOf("shutdown", "abort", "hibernate", "power-plan", "wake", "mode").forEach { action ->
            assertEquals(action, HistoryCategory.CONTROL, historyCategory(event(action)))
        }
    }

    @Test
    fun `historyCategory ordnet Routine und Plugin zu`() {
        assertEquals(HistoryCategory.ROUTINE, historyCategory(event("routine")))
        listOf("plugin", "games-scan", "game-config", "warnung").forEach { action ->
            assertEquals(action, HistoryCategory.PLUGIN, historyCategory(event(action)))
        }
    }

    @Test
    fun `historyCategory gibt Fehlern Vorrang`() {
        // Ein fehlgeschlagener Shutdown ist ein Fehler, keine Steuerung.
        assertEquals(HistoryCategory.ERROR, historyCategory(event("shutdown", ok = false)))
        assertEquals(HistoryCategory.ERROR, historyCategory(event("routine", ok = false)))
        assertEquals(HistoryCategory.ERROR, historyCategory(event("plugin", ok = false)))
        assertEquals(HistoryCategory.ERROR, historyCategory(event("warnung", ok = false)))
    }

    @Test
    fun `historyCategory ignoriert Gross-Kleinschreibung und Leerzeichen`() {
        assertEquals(HistoryCategory.CONTROL, historyCategory(event("  Shutdown ")))
        assertEquals(HistoryCategory.PLUGIN, historyCategory(event("GAMES-SCAN")))
        assertEquals(HistoryCategory.ROUTINE, historyCategory(event("Routine")))
    }

    @Test
    fun `historyCategory kennt unbekannte Aktionen als OTHER`() {
        // `ping` und `setup` gehoeren zu keinem Filter-Chip.
        assertEquals(HistoryCategory.OTHER, historyCategory(event("ping")))
        assertEquals(HistoryCategory.OTHER, historyCategory(event("setup")))
        assertEquals(HistoryCategory.OTHER, historyCategory(event("")))
    }

    // ---------------- F1: Filter und Suche ----------------

    private val mixed = listOf(
        event("shutdown", "PC wird heruntergefahren"),
        event("routine", "Feierabend: Energieplan Höchstleistung"),
        event("plugin", "game-optimizer gestartet"),
        event("wake", "Magic Packet gesendet", time = "2026-11-22 08:00:00"),
        event("abort", "Nichts abzubrechen", ok = false),
    )

    @Test
    fun `filterHistoryEvents ohne Filter liefert alles`() {
        assertEquals(mixed, filterHistoryEvents(mixed))
        assertEquals(mixed, filterHistoryEvents(mixed, HistoryCategory.ALL, ""))
    }

    @Test
    fun `filterHistoryEvents filtert nach Kategorie`() {
        val control = filterHistoryEvents(mixed, HistoryCategory.CONTROL)
        assertEquals(listOf("shutdown", "wake"), control.map { it.action })
        assertEquals(listOf("routine"), filterHistoryEvents(mixed, HistoryCategory.ROUTINE).map { it.action })
        assertEquals(listOf("plugin"), filterHistoryEvents(mixed, HistoryCategory.PLUGIN).map { it.action })
        // Fehler-Vorrang: `abort` ist ein Fehler, nicht Steuerung.
        assertEquals(listOf("abort"), filterHistoryEvents(mixed, HistoryCategory.ERROR).map { it.action })
    }

    @Test
    fun `filterHistoryEvents sucht ohne Gross-Kleinschreibung in Aktion und Text`() {
        assertEquals(listOf("shutdown"), filterHistoryEvents(mixed, query = "HERUNTER").map { it.action })
        assertEquals(listOf("routine"), filterHistoryEvents(mixed, query = "feierabend").map { it.action })
        assertEquals(listOf("wake"), filterHistoryEvents(mixed, query = "Wake").map { it.action })
        assertEquals(listOf("plugin"), filterHistoryEvents(mixed, query = "OPTIMIZER").map { it.action })
    }

    @Test
    fun `filterHistoryEvents trimmt die Suche und behandelt leere Suche als keinen Filter`() {
        assertEquals(mixed, filterHistoryEvents(mixed, query = "   "))
        assertEquals(listOf("shutdown"), filterHistoryEvents(mixed, query = "  shutdown  ").map { it.action })
    }

    @Test
    fun `filterHistoryEvents behandelt Sonderzeichen woertlich`() {
        val events = listOf(
            event("power-plan", "Energieplan auf 100% gesetzt"),
            event("plugin", "Fehler (Code 5)"),
            event("shutdown", "PC wird heruntergefahren"),
        )
        assertEquals(listOf("power-plan"), filterHistoryEvents(events, query = "100%").map { it.action })
        assertEquals(listOf("plugin"), filterHistoryEvents(events, query = "(code 5)").map { it.action })
        // Sonderzeichen sind kein Regex: ".*" findet nur echte Treffer.
        assertTrue(filterHistoryEvents(events, query = ".*").isEmpty())
    }

    @Test
    fun `filterHistoryEvents kombiniert Kategorie und Suche`() {
        assertEquals(
            listOf("shutdown"),
            filterHistoryEvents(mixed, HistoryCategory.CONTROL, "herunter").map { it.action },
        )
        assertTrue(filterHistoryEvents(mixed, HistoryCategory.ROUTINE, "herunter").isEmpty())
    }

    // ---------------- F1: Zeitgruppen ----------------

    @Test
    fun `groupHistoryEvents teilt Heute Gestern Aelter`() {
        val today = LocalDate.of(2026, 11, 23)
        val events = listOf(
            event("shutdown", time = "2026-11-23 14:05:00"),
            event("abort", time = "2026-11-22 23:59:00"),
            event("plugin", time = "2026-11-20 10:00:00"),
        )
        val sections = groupHistoryEvents(events, today)
        assertEquals(listOf(HistoryGroup.TODAY, HistoryGroup.YESTERDAY, HistoryGroup.OLDER), sections.map { it.group })
        assertEquals(listOf("shutdown"), sections[0].events.map { it.action })
        assertEquals(listOf("abort"), sections[1].events.map { it.action })
        assertEquals(listOf("plugin"), sections[2].events.map { it.action })
    }

    @Test
    fun `groupHistoryEvents laesst leere Gruppen weg`() {
        val today = LocalDate.of(2026, 11, 23)
        val sections = groupHistoryEvents(
            listOf(event("routine", time = "2026-11-23 09:00:00")),
            today,
        )
        assertEquals(1, sections.size)
        assertEquals(HistoryGroup.TODAY, sections[0].group)
        assertTrue(groupHistoryEvents(emptyList(), today).isEmpty())
    }

    @Test
    fun `groupHistoryEvents behaelt die Reihenfolge innerhalb einer Gruppe`() {
        val today = LocalDate.of(2026, 11, 23)
        val sections = groupHistoryEvents(
            listOf(
                event("shutdown", time = "2026-11-23 18:00:00"),
                event("wake", time = "2026-11-23 07:00:00"),
            ),
            today,
        )
        assertEquals(listOf("shutdown", "wake"), sections[0].events.map { it.action })
    }

    @Test
    fun `groupHistoryEvents sortiert fehlende und kaputte Zeitstempel nach Aelter`() {
        val today = LocalDate.of(2026, 11, 23)
        val events = listOf(
            event("shutdown", time = ""),
            event("abort", time = "kaputt"),
            event("wake", time = "2026-13-45 10:00:00"),
            event("routine", time = "2026-11-23"),
        )
        val sections = groupHistoryEvents(events, today)
        // "2026-11-23" ohne Uhrzeit ist ein gueltiges Datum und damit "Heute".
        assertEquals(HistoryGroup.TODAY, sections[0].group)
        assertEquals(listOf("routine"), sections[0].events.map { it.action })
        assertEquals(HistoryGroup.OLDER, sections[1].group)
        assertEquals(listOf("shutdown", "abort", "wake"), sections[1].events.map { it.action })
        assertNull(parseHistoryDate(event("wake", time = "kaputt")))
    }

    @Test
    fun `historyGroupOf behandelt ein Datum in der Zukunft als Heute`() {
        val today = LocalDate.of(2026, 11, 23)
        assertEquals(HistoryGroup.TODAY, historyGroupOf(event("shutdown", time = "2026-11-24 00:30:00"), today))
        assertEquals(LocalDate.of(2026, 11, 22), parseHistoryDate(event("x", time = "2026-11-22 23:59:59")))
    }

    // ---------------- F2: Export-Text ----------------

    @Test
    fun `historyExportLine enthaelt Datum Uhrzeit Aktion und Text`() {
        assertEquals(
            "2026-11-23 14:05:00 | shutdown | PC wird heruntergefahren",
            historyExportLine(event("shutdown", "PC wird heruntergefahren")),
        )
    }

    @Test
    fun `historyExportLine markiert Fehler und laesst leere Felder weg`() {
        assertEquals(
            "2026-11-23 14:05:00 | abort | Nichts abzubrechen | Fehler",
            historyExportLine(event("abort", "Nichts abzubrechen", ok = false)),
        )
        // Ohne Text bleibt nur Zeit, Aktion und das Fehler-Kennzeichen.
        assertEquals(
            "2026-11-23 14:05:00 | abort | Fehler",
            historyExportLine(event("abort", "", ok = false)),
        )
        // Fehlende Zeit/Aktion werden zum Platzhalter, Zeilenumbrueche zu Leerzeichen.
        assertEquals(
            "- | - | Zeile 1 Zeile 2",
            historyExportLine(AgentEvent(time = "", action = "", detail = "Zeile 1\nZeile 2")),
        )
    }

    @Test
    fun `historyExportText schreibt Kopfzeile und alle Zeilen`() {
        val text = historyExportText(
            pcName = "Wohnzimmer",
            exportedAt = "23.11.2026 14:05",
            events = listOf(
                event("shutdown", "PC wird heruntergefahren"),
                event("abort", "", ok = false),
            ),
        )
        val lines = text.trimEnd('\n').split("\n")
        assertEquals("PC Steuerung - Verlauf", lines[0])
        assertEquals("PC: Wohnzimmer", lines[1])
        assertEquals("Exportiert: 23.11.2026 14:05", lines[2])
        assertEquals("Einträge: 2", lines[3])
        assertEquals("", lines[4])
        assertEquals("2026-11-23 14:05:00 | shutdown | PC wird heruntergefahren", lines[5])
        assertEquals("2026-11-23 14:05:00 | abort | Fehler", lines[6])
        assertEquals(7, lines.size)
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `historyExportText bei leerer Liste`() {
        val text = historyExportText("", "", emptyList())
        val lines = text.trimEnd('\n').split("\n")
        // Leerer PC-Name und Zeitpunkt werden ersetzt, die Liste hat einen Hinweis.
        assertEquals("PC: PC", lines[1])
        assertEquals("Exportiert: -", lines[2])
        assertEquals("Einträge: 0", lines[3])
        assertEquals("Keine Einträge für diesen Filter.", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun `historyExportFileName und Zeitstempel`() {
        assertEquals("verlauf-2026-11-23.txt", historyExportFileName(LocalDate.of(2026, 11, 23)))
        assertEquals(
            "03.01.2026 07:04",
            historyExportTimestamp(LocalDateTime.of(2026, 1, 3, 7, 4)),
        )
    }

    // ---------------- F3: Einstellung history_lines ----------------

    @Test
    fun `decodeHistoryLines faellt bei fehlenden oder ungueltigen Werten auf 200 zurueck`() {
        assertEquals(200, HISTORY_LINES_DEFAULT)
        assertEquals(200, decodeHistoryLines(null))
        assertEquals(200, decodeHistoryLines(""))
        assertEquals(200, decodeHistoryLines("   "))
        assertEquals(200, decodeHistoryLines("abc"))
        assertEquals(200, decodeHistoryLines("300"))
        assertEquals(200, decodeHistoryLines("-200"))
        assertEquals(200, normalizeHistoryLines(0))
        assertEquals(200, AppSettings().historyLines)
    }

    @Test
    fun `encodeHistoryLines und decodeHistoryLines sind zueinander passend`() {
        HISTORY_LINES_CHOICES.forEach { lines ->
            assertEquals(lines, decodeHistoryLines(encodeHistoryLines(lines)))
        }
        assertEquals("500", encodeHistoryLines(500))
        assertEquals("1000", encodeHistoryLines(1000))
        // Ungewoehnliche Werte werden schon beim Speichern korrigiert.
        assertEquals("200", encodeHistoryLines(12345))
        assertEquals(1000, normalizeHistoryLines(1000))
    }
}

