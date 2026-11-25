package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessesTest {

    @Test
    fun `parseRunningApps liest und sortiert sichtbare Prozesse`() {
        val apps = parseRunningApps(
            """{"processes":[{"pid":321,"name":"ollama.exe"},{"pid":123,"name":"Jan.exe"}]}"""
        )

        assertEquals(listOf(RunningApp(123, "Jan.exe"), RunningApp(321, "ollama.exe")), apps)
    }

    @Test
    fun `parseRunningApps verwirft kritische IDs leere Namen und Duplikate`() {
        val apps = parseRunningApps(
            """{"processes":[{"pid":4,"name":"System"},{"pid":25,"name":""},
                {"pid":42,"name":"app.exe"},{"pid":42,"name":"other.exe"}]}"""
        )

        assertEquals(listOf(RunningApp(42, "app.exe")), apps)
    }

    @Test
    fun `parseRunningApps liefert bei alten Agenten und kaputtem JSON eine leere Liste`() {
        assertTrue(parseRunningApps("HTTP 404: not found").isEmpty())
        assertTrue(parseRunningApps("kein json").isEmpty())
    }

    @Test
    fun `processCloseBody bindet die Anfrage an den angezeigten Namen`() {
        assertEquals(
            """{"expected_name":"ollama.exe"}""",
            processCloseBody(RunningApp(321, "ollama.exe")),
        )
    }
}
