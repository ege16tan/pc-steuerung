package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dauerkarte: der Knopf "Herunterfahren" darf nie ohne Countdown ausloesen
 * (Fehlgriff auf dem Sperrbildschirm) und nie unsinnige Werte schicken.
 */
class LiveActionTest {

    @Test
    fun usesDelayFromProfile() {
        assertEquals(60, liveShutdownDelay(60))
        assertEquals("/shutdown?delay=30", liveShutdownPath(30))
    }

    @Test
    fun keepsMinimumCountdown() {
        // "Sofort" (0) aus dem Profil wird in der Karte zu 10 s.
        assertEquals(10, liveShutdownDelay(0))
        assertEquals("/shutdown?delay=10", liveShutdownPath(0))
        assertEquals("/shutdown?delay=10", liveShutdownPath(-5))
    }

    @Test
    fun capsLongDelays() {
        assertEquals(3_600, liveShutdownDelay(99_999))
        assertEquals("/shutdown?delay=3600", liveShutdownPath(99_999))
    }
}
