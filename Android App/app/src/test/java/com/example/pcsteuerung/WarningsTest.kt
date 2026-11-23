package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Warnungen (Phase C): Parser, Einmal-Meldung und Begrenzung des Verlaufs.
 * Reine JVM-Tests ohne Android-Rahmen (wie `SensorsTest`).
 */
class WarningsTest {

    private val body = """
        {
          "rules": [{"id": "cpu-high", "label": "CPU-Last", "metric": "cpu_percent",
                     "op": ">", "threshold": 90.0, "duration_s": 30, "enabled": true}],
          "active": [],
          "triggered": [
            {"id": "cpu-high", "label": "CPU-Last",
             "message": "CPU-Last: 96.1 % > 90.0 % seit 30 s",
             "value": 96.1, "threshold": 90.0, "op": ">", "duration_s": 30,
             "since_s": 31, "since": "2026-11-23 12:00:00", "since_epoch": 1763000100.0},
            {"id": "ram-high", "label": "Arbeitsspeicher",
             "message": "Arbeitsspeicher: 93.0 % > 90.0 % seit 60 s",
             "value": 93.0, "threshold": 90.0, "op": ">", "duration_s": 60,
             "since_s": 60, "since": "2026-11-23 12:05:00", "since_epoch": 1763000400.0}
          ],
          "checked_at": "2026-11-23 12:05:30",
          "interval_s": 30
        }
    """.trimIndent()

    @Test
    fun parsesTriggeredAlerts() {
        val alerts = parseWarningAlerts(body)
        assertEquals(2, alerts.size)
        assertEquals("cpu-high", alerts[0].id)
        assertEquals("CPU-Last", alerts[0].label)
        assertTrue(alerts[0].message.contains("96.1"))
        assertEquals(1763000100L, alerts[0].epoch)
        assertEquals(1763000400L, alerts[1].epoch)
    }

    @Test
    fun brokenAnswersYieldEmptyList() {
        assertTrue(parseWarningAlerts("").isEmpty())
        assertTrue(parseWarningAlerts("HTTP 404").isEmpty())
        assertTrue(parseWarningAlerts("{kaputt").isEmpty())
        // Alter Agent: kein "triggered" -> nichts zu melden.
        assertTrue(parseWarningAlerts("""{"rules":[]}""").isEmpty())
    }

    @Test
    fun skipsEntriesWithoutMessageOrEpoch() {
        val text = """
            {"triggered": [
              {"id": "x", "label": "", "message": "", "since_epoch": 1763000100.0},
              {"id": "y", "label": "Y", "message": "Y: 99 % > 90 %", "since_epoch": 0},
              {"id": "z", "label": "Z", "message": "Z: 99 % > 90 %", "since_epoch": 1763000200.0}
            ]}
        """.trimIndent()
        val alerts = parseWarningAlerts(text)
        assertEquals(1, alerts.size)
        assertEquals("z", alerts[0].id)
    }

    @Test
    fun blankMessageFallsBackToLabel() {
        val text = """{"triggered": [{"id": "l", "label": "Luefter", "message": "", "since_epoch": 1763000100.0}]}"""
        val alerts = parseWarningAlerts(text)
        assertEquals(1, alerts.size)
        assertEquals("Luefter", alerts[0].message)
    }

    @Test
    fun onlyUnseenAlertsAreNew() {
        val alerts = parseWarningAlerts(body)
        val seen = setOf(1763000100L)
        val fresh = newWarningAlerts(alerts, seen)
        assertEquals(1, fresh.size)
        assertEquals(1763000400L, fresh[0].epoch)
        // Alles gemerkt: nichts mehr melden (eine Meldung je Ereignis).
        assertTrue(newWarningAlerts(alerts, setOf(1763000100L, 1763000400L)).isEmpty())
    }

    @Test
    fun catchUpIsLimitedToNewestAlerts() {
        val alerts = (1..10).map {
            WarningAlert("id-$it", "CPU", "CPU: $it", 1_763_000_000L + it)
        }
        val fresh = newWarningAlerts(alerts, emptySet(), limit = 3)
        assertEquals(3, fresh.size)
        // Die drei neuesten, aeltester zuerst.
        assertEquals(1_763_000_008L, fresh[0].epoch)
        assertEquals(1_763_000_010L, fresh[2].epoch)
    }

    @Test
    fun seenEpochsStayBounded() {
        val alerts = (1..80).map {
            WarningAlert("id-$it", "CPU", "CPU: $it", 1_763_000_000L + it)
        }
        val epochs = warningEpochs(alerts, limit = 60)
        assertEquals(60, epochs.size)
        assertFalse(epochs.contains(1_763_000_001L))
        assertTrue(epochs.contains(1_763_000_080L))
    }
}
