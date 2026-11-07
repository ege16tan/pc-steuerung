package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefungen fuer den Marktplatz: Katalog-Parsing (Robustheit gegen
 * kaputte/faulsche JSON-Eintraege) und Zusammenfuehrung mit dem lokalen
 * Fallback (Remote gewinnt, Fehlendes wird ergaenzt).
 */
class ModulesTest {

    @Test
    fun `parseCatalog liest saemtliche Felder aus`() {
        val json = """
            {
              "catalogVersion": 1,
              "modules": [
                {
                  "id": "timer",
                  "name": "Zeitschaltuhr",
                  "description": "Herunterfahren planen.",
                  "version": 2
                },
                {
                  "id": "log",
                  "name": "Verlauf",
                  "description": "Log ansehen.",
                  "version": 1
                }
              ]
            }
        """.trimIndent()

        val modules = parseCatalog(json)

        assertEquals(2, modules.size)
        val timer = modules.first { it.id == MODULE_TIMER }
        assertEquals("Zeitschaltuhr", timer.name)
        assertEquals("Herunterfahren planen.", timer.description)
        assertEquals(2, timer.version)
        val log = modules.first { it.id == MODULE_LOG }
        assertEquals(1, log.version)
    }

    @Test
    fun `parseCatalog fuellt fehlende Felder mit Standards`() {
        val json = """
            { "modules": [ { "id": "screenshot", "description": "Screenshots schießen." } ] }
        """.trimIndent()

        val modules = parseCatalog(json)

        assertEquals(1, modules.size)
        assertEquals("screenshot", modules[0].id)
        // Standardwerte: Name und Version werden mit Defaults belegt.
        assertEquals("PC-Modul", modules[0].name)
        assertEquals(1, modules[0].version)
        assertEquals("Screenshots schießen.", modules[0].description)
    }

    @Test
    fun `parseCatalog ueberspringt Eintraege ohne ID`() {
        val json = """
            {
              "modules": [
                { "name": "Ohne ID", "description": "kein id-Feld" },
                { "id": "log", "name": "Verlauf" },
                { "id": "  ", "name": "Nur Leerzeichen" }
              ]
            }
        """.trimIndent()

        val modules = parseCatalog(json)

        assertEquals(1, modules.size)
        assertEquals(MODULE_LOG, modules[0].id)
    }

    @Test
    fun `parseCatalog akzeptiert jede ID nur einmal - erster gewinnt`() {
        val json = """
            {
              "modules": [
                { "id": "timer", "name": "Erste Fassung", "version": 1 },
                { "id": "timer", "name": "Zweite Fassung", "version": 9 }
              ]
            }
        """.trimIndent()

        val modules = parseCatalog(json)

        assertEquals(1, modules.size)
        assertEquals("Erste Fassung", modules[0].name)
        assertEquals(1, modules[0].version)
    }

    @Test
    fun `parseCatalog gibt leere Liste bei fehlendem modules Feld`() {
        val modules = parseCatalog("""{ "catalogVersion": 1 }""")

        assertTrue(modules.isEmpty())
    }

    @Test
    fun `mergeCatalog - remote gewinnt, local ergaenzt`() {
        val local = defaultCatalog()
        val remote = listOf(
            CatalogModule(id = MODULE_TIMER, name = "Zeitschaltuhr (neu)", description = "", version = 2),
        )

        val merged = mergeCatalog(local, remote)

        assertEquals(2, merged.size)
        assertTrue(
            "Remote gewinnt fuer existierende IDs",
            merged.none { it.id == MODULE_TIMER && it.version != 2 },
        )
        // Lokal vorhandenes Modul bleibt ergaenzt, wenn Remote es nicht hat.
        assertTrue(merged.any { it.id == MODULE_LOG })
    }

    @Test
    fun `mergeCatalog - ohne Remote kommt nur der lokale Fallback`() {
        val local = defaultCatalog()

        val merged = mergeCatalog(local, listOf())

        assertEquals(local, merged)
    }

    @Test
    fun `defaultCatalog deckt nur unterstuetzte Module ab`() {
        val ids = defaultCatalog().map { it.id }

        assertTrue(ids.containsAll(listOf(MODULE_TIMER, MODULE_LOG)))
        ids.forEach { id ->
            assertTrue(
                "Modul $id muss von der App unterstuetzt werden",
                id in SUPPORTED_MODULES,
            )
        }
        // Basis-Features sind bewusst KEINE Module:
        assertFalse(SUPPORTED_MODULES.contains("hibernate"))
        assertFalse(SUPPORTED_MODULES.contains("power_plan"))
    }
}
