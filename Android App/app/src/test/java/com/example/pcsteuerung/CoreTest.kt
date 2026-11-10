package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    @Test
    fun macValid_acceptsColonFormat() {
        assertTrue(isValidMac("AA:BB:CC:DD:EE:FF"))
        assertTrue(isValidMac("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun macValid_acceptsDashFormat() {
        assertTrue(isValidMac("AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun macValid_acceptsBlank() {
        assertTrue(isValidMac(""))
        assertTrue(isValidMac("   "))
    }

    @Test
    fun macValid_rejectsBadFormats() {
        assertFalse(isValidMac("AA:BB:CC:DD:EE"))
        assertFalse(isValidMac("AA:BB:CC:DD:EE:FF:11"))
        assertFalse(isValidMac("GG:BB:CC:DD:EE:FF"))
        assertFalse(isValidMac("hello"))
    }

    @Test
    fun portValid_acceptsValidPorts() {
        assertTrue(isValidPort("8000"))
        assertTrue(isValidPort("1"))
        assertTrue(isValidPort("65535"))
        assertTrue(isValidPort(" 8080 "))
    }

    @Test
    fun portValid_rejectsInvalidPorts() {
        assertFalse(isValidPort(""))
        assertFalse(isValidPort("0"))
        assertFalse(isValidPort("65536"))
        assertFalse(isValidPort("-1"))
        assertFalse(isValidPort("abc"))
    }

    @Test
    fun duration_formatsSeconds() {
        assertEquals("0 s", fmtDuration(0))
        assertEquals("59 s", fmtDuration(59))
    }

    @Test
    fun duration_formatsMinutes() {
        assertEquals("1 min", fmtDuration(60))
        assertEquals("59 min", fmtDuration(3599))
    }

    @Test
    fun duration_formatsHours() {
        assertEquals("1 h 0 min", fmtDuration(3600))
        assertEquals("2 h 30 min", fmtDuration(9000))
    }

    @Test
    fun duration_clampsNegativeToZero() {
        assertEquals("0 s", fmtDuration(-5))
    }

    @Test
    fun planLabel_mapsKnownPlans() {
        assertEquals("Höchstleistung", planLabel("high"))
        assertEquals("Ausbalanciert", planLabel("balanced"))
        assertEquals("Energiesparen", planLabel("saver"))
        assertEquals("Anderer Plan", planLabel("anderer"))
        assertEquals("Unbekannt", planLabel("unbekannt"))
        assertEquals("Unbekannt", planLabel("?"))
    }

    @Test
    fun planLabel_passesThroughUnknown() {
        assertEquals("custom", planLabel("custom"))
    }

    @Test
    fun status_parsesOnlineJson() {
        val state = parseStatus("""{"power_plan":"balanced","pending_shutdown":120,"simulate":true}""")
        assertEquals(PcStatus.ONLINE, state.status)
        assertEquals("balanced", state.plan)
        assertEquals(120, state.pending)
        assertEquals(true, state.simulate)
        assertEquals("Online · Ausbalanciert", state.text)
    }

    @Test
    fun status_mapsUnauthorized() {
        val state = parseStatus("HTTP 401: wrong")
        assertEquals(PcStatus.ERROR, state.status)
        assertEquals("Login fehlt oder falsch", state.text)
    }

    @Test
    fun status_mapsForbidden() {
        val state = parseStatus("HTTP 403: denied")
        assertEquals(PcStatus.ERROR, state.status)
        assertEquals("Zugriff verweigert", state.text)
    }

    @Test
    fun status_mapsOtherHttpError() {
        val state = parseStatus("HTTP 500: kaputt")
        assertEquals(PcStatus.ERROR, state.status)
        assertEquals("HTTP 500: kaputt", state.text)
    }

    @Test
    fun status_handlesMalformedJson() {
        val state = parseStatus("kein json")
        assertEquals(PcStatus.ERROR, state.status)
        assertEquals("Antwort unlesbar", state.text)
    }

    @Test
    fun setupQr_parsesNameHostPort() {
        val qr = parsePcSetupQr(
            """{"app":"pc-steuerung","version":1,"host":"192.168.178.20","port":8000,"name":"EVGA"}"""
        )
        assertEquals("EVGA", qr?.name)
        assertEquals("192.168.178.20", qr?.host)
        assertEquals(8000, qr?.port)
        assertEquals(null, qr?.mac)
    }

    @Test
    fun setupQr_readsOptionalMac() {
        val qr = parsePcSetupQr(
            """{"app":"pc-steuerung","version":1,"host":"192.168.178.20","port":8000,"name":"EVGA","mac":"aa:bb:cc:dd:ee:ff"}"""
        )
        assertEquals("AA:BB:CC:DD:EE:FF", qr?.mac)
    }

    @Test
    fun setupQr_rejectsWrongAppOrHost() {
        assertEquals(null, parsePcSetupQr("""{"app":"other","version":1,"host":"192.168.1.2","port":8000}"""))
        assertEquals(null, parsePcSetupQr("""{"app":"pc-steuerung","version":1,"host":"192.168.1","port":8000}"""))
        assertEquals(null, parsePcSetupQr("""{"app":"pc-steuerung","version":1,"host":"192.168.1.2","port":0}"""))
    }

    @Test
    fun pairingInfo_readsMacOnlyWhenWolEnabled() {
        val enabled = parsePairingMac(
            """{"ready":true,"wol_enabled":true,"mac":"AA:BB:CC:DD:EE:FF"}"""
        )
        assertEquals("AA:BB:CC:DD:EE:FF", enabled)
        assertEquals(
            null,
            parsePairingMac("""{"ready":true,"wol_enabled":false,"mac":"AA:BB:CC:DD:EE:FF"}""")
        )
        assertEquals(null, parsePairingMac("""{"ready":false}"""))
    }
}
