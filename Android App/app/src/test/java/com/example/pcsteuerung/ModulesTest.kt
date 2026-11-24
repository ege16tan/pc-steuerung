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

    // -----------------------------------------------------------------------
    // Versionsspeicher und Update-Hinweis (Phase F5)
    // -----------------------------------------------------------------------

    @Test
    fun `installedVersions liefert leeres Mapping ohne Speicher`() {
        assertTrue(installedVersions(null).isEmpty())
        assertTrue(installedVersions("").isEmpty())
        assertTrue(installedVersions("   ").isEmpty())
    }

    @Test
    fun `installedVersions ignoriert kaputten Speicher`() {
        // Kein JSON und ein Array statt Objekt duerfen nicht werfen.
        assertTrue(installedVersions("kein json").isEmpty())
        assertTrue(installedVersions("[1,2,3]").isEmpty())
    }

    @Test
    fun `versionsJson und installedVersions sind ein Round-Trip`() {
        val raw = versionsJson(mapOf(MODULE_TIMER to 3, MODULE_LOG to 1))

        assertEquals(mapOf(MODULE_LOG to 1, MODULE_TIMER to 3), installedVersions(raw))
        // Kanonisch sortiert: gleicher Inhalt ergibt wieder dieselbe Zeichenkette.
        assertEquals(raw, versionsJson(installedVersions(raw)))
    }

    @Test
    fun `withInstalledVersion schreibt die Katalogversion`() {
        val raw = withInstalledVersion(null, MODULE_TIMER, 4)

        assertEquals(4, installedVersionOf(installedVersions(raw), MODULE_TIMER))

        // Ein zweites Modul kommt dazu, das erste bleibt unberuehrt.
        val both = withInstalledVersion(raw, MODULE_LOG, 2)
        assertEquals(mapOf(MODULE_LOG to 2, MODULE_TIMER to 4), installedVersions(both))
    }

    @Test
    fun `withInstalledVersion hebt eine alte Version an`() {
        val raw = withInstalledVersion(null, MODULE_TIMER, 1)

        val updated = withInstalledVersion(raw, MODULE_TIMER, 2)

        assertEquals(2, installedVersionOf(installedVersions(updated), MODULE_TIMER))
    }

    @Test
    fun `withoutInstalledVersion loescht nur den eigenen Eintrag`() {
        val raw = withInstalledVersion(
            withInstalledVersion(null, MODULE_TIMER, 2),
            MODULE_LOG,
            1,
        )

        val removed = withoutInstalledVersion(raw, MODULE_TIMER)

        assertEquals(mapOf(MODULE_LOG to 1), installedVersions(removed))
        // Entfernen eines unbekannten Moduls aendert nichts.
        assertEquals(removed, withoutInstalledVersion(removed, "unbekannt"))
        // Leerer Speicher bleibt ein leeres Objekt.
        assertEquals("{}", withoutInstalledVersion(null, MODULE_TIMER))
    }

    @Test
    fun `Abwaertskompatibilitaet - ohne Eintrag gilt Version 1`() {
        // Altdaten kannten nur `installed_modules`, also gar keinen Eintrag.
        val versions = installedVersions(null)

        assertEquals(MODULE_VERSION_FALLBACK, installedVersionOf(versions, MODULE_TIMER))
        assertEquals(1, installedVersionOf(versions, MODULE_LOG))
    }

    @Test
    fun `Version 0 im Speicher wird auf 1 gehoben`() {
        val versions = installedVersions("""{"timer": 0}""")

        assertEquals(1, installedVersionOf(versions, MODULE_TIMER))
    }
}
