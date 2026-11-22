package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefungen fuer die Spiele-Optimierung: Parser von `GET /games` und
 * `GET /game-config` (tolerant gegen fehlende Felder und kaputtes JSON) sowie
 * die reine Umwandlung "Ausblenden/Einblenden" und die manuellen Spiele.
 */
class GamesTest {

    // ---------------- GET /games ----------------

    @Test
    fun `parseGames liest alle Felder inklusive hidden`() {
        val body = """
            {"scanned_at":"2026-01-01 10:00:00","games":[
              {"name":"Cyberpunk 2077","exe":"D:\\Games\\Cyberpunk.exe","source":"Steam",
               "running":true,"hidden":false},
              {"name":"Alt","exe":"D:\\Games\\Alt.exe","source":"GOG","running":false,"hidden":true}
            ]}
        """
        val games = parseGames(body)
        assertEquals(2, games.size)
        assertEquals("Cyberpunk 2077", games[0].name)
        assertEquals("D:\\Games\\Cyberpunk.exe", games[0].exe)
        assertEquals("Steam", games[0].source)
        assertTrue(games[0].running)
        assertFalse(games[0].hidden)
        assertTrue(games[1].hidden)
    }

    @Test
    fun `parseGames akzeptiert manuelle Spiele mit Quelle manuell`() {
        val games = parseGames(
            """{"games":[{"name":"Mein Spiel","exe":"D:\\Spiele\\game.exe","source":"manuell"}]}"""
        )
        assertEquals(1, games.size)
        assertEquals("manuell", games[0].source)
        assertTrue(games[0].isManual)
    }

    @Test
    fun `parseGames nimmt auch eine reine Liste`() {
        val games = parseGames("""[{"name":"A","exe":"C:\\a.exe"},{"name":"B","exe":"C:\\b.exe"}]""")
        assertEquals(2, games.size)
    }

    @Test
    fun `parseGames toleriert fehlende Felder`() {
        val games = parseGames("""{"games":[{"name":"Nur Name"},{"exe":"C:\\nur.exe"}]}""")
        assertEquals(2, games.size)
        assertEquals("Nur Name", games[0].name)
        assertEquals("", games[0].exe)
        assertFalse(games[0].hidden)
        assertFalse(games[0].running)
        assertEquals("", games[0].source)
    }

    @Test
    fun `parseGames ueberspringt leere Eintraege`() {
        val games = parseGames("""{"games":[{},{"name":""},{"name":"Ok","exe":"C:\\ok.exe"}]}""")
        assertEquals(1, games.size)
        assertEquals("Ok", games[0].name)
    }

    @Test
    fun `parseGames liefert bei Fehlern eine leere Liste`() {
        assertTrue(parseGames("HTTP 404: not found").isEmpty())
        assertTrue(parseGames("").isEmpty())
        assertTrue(parseGames("kein json").isEmpty())
        assertTrue(parseGames("""{"scanned_at":"x"}""").isEmpty())
    }

    // ---------------- GET /game-config ----------------

    @Test
    fun `parseGameConfig liest den kompletten Zustand`() {
        val body = """
            {"kill_list":["Discord.exe","Spotify.exe"],"scan_paths":["D:\\Spiele"],
             "auto":true,"hidden":["D:\\Games\\Alt.exe"],
             "custom":[{"name":"Mein Spiel","exe":"D:\\Spiele\\game.exe","source":"manuell"}]}
        """
        val config = parseGameConfig(body)
        assertEquals(listOf("Discord.exe", "Spotify.exe"), config?.killList)
        assertEquals(listOf("D:\\Spiele"), config?.scanPaths)
        assertEquals(true, config?.auto)
        assertEquals(listOf("D:\\Games\\Alt.exe"), config?.hidden)
        assertEquals(1, config?.custom?.size)
        assertEquals("Mein Spiel", config?.custom?.first()?.name)
        assertEquals("manuell", config?.custom?.first()?.source)
        assertTrue(config?.custom?.first()?.isManual == true)
    }

    @Test
    fun `parseGameConfig fuellt fehlende Felder mit Defaults`() {
        val config = parseGameConfig("""{"kill_list":["Discord.exe"]}""")
        assertEquals(listOf("Discord.exe"), config?.killList)
        assertTrue(config?.scanPaths?.isEmpty() == true)
        assertTrue(config?.hidden?.isEmpty() == true)
        assertTrue(config?.custom?.isEmpty() == true)
        assertEquals(false, config?.auto)
    }

    @Test
    fun `parseGameConfig liefert null bei HTTP-Fehler und kaputtem JSON`() {
        assertNull(parseGameConfig("HTTP 404: not found"))
        assertNull(parseGameConfig("kein json"))
        assertNull(parseGameConfig(""))
    }

    @Test
    fun `gameConfigJson schreibt den kompletten Zustand`() {
        val config = GameConfig(
            killList = listOf("Discord.exe"),
            scanPaths = emptyList(),
            auto = false,
            hidden = listOf("D:\\Games\\Alt.exe"),
            custom = listOf(
                GameEntry(
                    name = "Mein Spiel",
                    exe = "D:\\Spiele\\game.exe",
                    source = GAME_SOURCE_MANUAL,
                )
            ),
        )
        val json = gameConfigJson(config)
        assertTrue(json.contains("\"kill_list\""))
        assertTrue(json.contains("\"scan_paths\""))
        assertTrue(json.contains("\"auto\":false"))
        assertTrue(json.contains("\"hidden\""))
        // Manuelle Spiele bekommen immer die Quelle "manuell".
        assertTrue(json.contains("\"source\":\"manuell\""))
        // Hin und zurueck ergibt denselben Zustand.
        assertEquals(config, parseGameConfig(json))
    }

    // ---------------- Ausblenden / Einblenden ----------------

    private val cyberpunk = GameEntry(
        name = "Cyberpunk 2077",
        exe = "D:\\Games\\Cyberpunk.exe",
        source = "Steam",
    )

    @Test
    fun `withHidden haengt den Pfad an`() {
        val next = withHidden(emptyList(), cyberpunk, hide = true)
        assertEquals(listOf("D:\\Games\\Cyberpunk.exe"), next)
    }

    @Test
    fun `withHidden legt keinen doppelten Eintrag an`() {
        val once = withHidden(emptyList(), cyberpunk, hide = true)
        val twice = withHidden(once, cyberpunk, hide = true)
        assertEquals(1, twice.size)
        assertEquals(once, twice)
    }

    @Test
    fun `withHidden entfernt beim Einblenden wieder`() {
        val hidden = listOf("D:\\Games\\Alt.exe", "D:\\Games\\Cyberpunk.exe")
        val next = withHidden(hidden, cyberpunk, hide = false)
        assertEquals(listOf("D:\\Games\\Alt.exe"), next)
    }

    @Test
    fun `withHidden vergleicht unabhaengig von Schreibweise und Schraegstrich`() {
        val existing = listOf("d:/games/CYBERPUNK.exe")
        assertTrue(withHidden(existing, cyberpunk, hide = false).isEmpty())
        // und beim Ausblenden bleibt es bei einem Eintrag
        assertEquals(1, withHidden(existing, cyberpunk, hide = true).size)
    }

    @Test
    fun `withHidden laesst die Eingabeliste unveraendert`() {
        val hidden = listOf("D:\\Games\\Alt.exe")
        withHidden(hidden, cyberpunk, hide = true)
        assertEquals(listOf("D:\\Games\\Alt.exe"), hidden)
    }

    @Test
    fun `withHidden nimmt ohne EXE den Namen`() {
        val entry = GameEntry(name = "Ohne Pfad", exe = "")
        assertEquals(listOf("Ohne Pfad"), withHidden(emptyList(), entry, hide = true))
    }

    @Test
    fun `isHiddenGame prueft Kennzeichen und Konfiguration`() {
        assertTrue(isHiddenGame(cyberpunk.copy(hidden = true), emptyList()))
        assertFalse(isHiddenGame(cyberpunk, emptyList()))
        assertTrue(isHiddenGame(cyberpunk, listOf("D:\\Games\\Cyberpunk.exe")))
        assertTrue(isHiddenGame(cyberpunk, listOf("cyberpunk 2077")))
        assertFalse(isHiddenGame(cyberpunk, listOf("D:\\Games\\Anderes.exe")))
        // leere Eintraege duerfen nicht alles ausblenden
        assertFalse(isHiddenGame(cyberpunk, listOf("", "   ")))
    }

    // ---------------- Manuelle Spiele (custom) ----------------

    @Test
    fun `withCustomGame nimmt ein Spiel mit Quelle manuell auf`() {
        val config = withCustomGame(
            GameConfig(),
            GameEntry(name = "Mein Spiel", exe = "D:\\Spiele\\game.exe"),
        )
        assertEquals(1, config.custom.size)
        assertEquals("manuell", config.custom.first().source)
    }

    @Test
    fun `withCustomGame ersetzt denselben Pfad statt zu doppeln`() {
        val first = withCustomGame(
            GameConfig(),
            GameEntry(name = "Alt", exe = "D:\\Spiele\\game.exe"),
        )
        val second = withCustomGame(first, GameEntry(name = "Neu", exe = "d:/spiele/GAME.exe"))
        assertEquals(1, second.custom.size)
        assertEquals("Neu", second.custom.first().name)
    }

    @Test
    fun `withoutCustomGame entfernt nur den passenden Eintrag`() {
        val config = GameConfig(
            custom = listOf(
                GameEntry(name = "A", exe = "D:\\A.exe", source = "manuell"),
                GameEntry(name = "B", exe = "D:\\B.exe", source = "manuell"),
            )
        )
        val next = withoutCustomGame(config, GameEntry(name = "A", exe = "d:/a.exe"))
        assertEquals(1, next.custom.size)
        assertEquals("B", next.custom.first().name)
    }

    @Test
    fun `mergeCustomGames ergaenzt ohne doppelte Pfade`() {
        val scanned = listOf(GameEntry(name = "Scan", exe = "D:\\Games\\Scan.exe", source = "Steam"))
        val custom = listOf(
            GameEntry(name = "Scan", exe = "d:/games/scan.exe"),
            GameEntry(name = "Mein Spiel", exe = "D:\\Spiele\\game.exe"),
        )
        val merged = mergeCustomGames(scanned, custom)
        assertEquals(2, merged.size)
        assertEquals("Mein Spiel", merged[1].name)
        assertEquals("manuell", merged[1].source)
    }

    // ---------------- Kill-Liste ----------------

    @Test
    fun `withKillEntry nimmt einen Namen auf und entfernt ihn wieder`() {
        val added = withKillEntry(listOf("Discord.exe"), "Spotify.exe", present = true)
        assertEquals(listOf("Discord.exe", "Spotify.exe"), added)
        val removed = withKillEntry(added, "spotify.exe", present = false)
        assertEquals(listOf("Discord.exe"), removed)
    }

    @Test
    fun `withKillEntry ignoriert leere Namen und Doppelte`() {
        val list = listOf("Discord.exe")
        assertEquals(list, withKillEntry(list, "   ", present = true))
        assertEquals(listOf("Discord.exe"), withKillEntry(list, "discord.exe", present = true))
    }

    // ---------------- Ausgabe eines Plugins ----------------

    @Test
    fun `parsePluginOutput schneidet Leerzeilen am Ende ab`() {
        assertEquals("Zeile 1\nZeile 2", parsePluginOutput("Zeile 1\nZeile 2\n\n"))
        assertEquals("", parsePluginOutput(""))
        // Fehlerantworten bleiben sichtbar
        assertEquals("HTTP 404: not found", parsePluginOutput("HTTP 404: not found\n"))
    }
}
