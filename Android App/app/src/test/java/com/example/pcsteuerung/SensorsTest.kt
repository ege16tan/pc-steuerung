package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorsTest {

    /** Vollstaendige Antwort laut Vertrag von `GET /sensors`. */
    private val fullJson = """
        {
          "cpu": {"available": true, "percent": 12.3, "cores": 16},
          "ram": {"available": true, "used_mb": 9011, "total_mb": 32768, "percent": 27.5},
          "gpu": {"available": true, "name": "RTX 4070", "percent": 34.0,
                  "memory_used_mb": 2048, "memory_total_mb": 8192},
          "network": {"available": true, "name": "Ethernet", "rx_bps": 12000.0, "tx_bps": 3000.0},
          "disks": [{"name": "C:", "total_gb": 931.5, "free_gb": 250.3, "percent": 73.1}],
          "fans": {"available": false, "reason": "kein Lueftersensor"},
          "temperatures": {"available": false, "reason": "kein Temperatursensor"}
        }
    """.trimIndent()

    @Test
    fun sensors_parsesCompleteJson() {
        val status = parseSensors(fullJson)
        assertTrue(status.cpu.available)
        assertEquals(12.3, status.cpu.percent ?: 0.0, 0.001)
        assertEquals(16, status.cpu.cores)
        assertTrue(status.ram.available)
        assertEquals(9011L, status.ram.usedMb)
        assertEquals(32768L, status.ram.totalMb)
        assertEquals(27.5, status.ram.percent ?: 0.0, 0.001)
        assertTrue(status.gpu.available)
        assertEquals("RTX 4070", status.gpu.name)
        assertEquals(2048L, status.gpu.memoryUsedMb)
        assertEquals(8192L, status.gpu.memoryTotalMb)
        assertTrue(status.network.available)
        assertEquals("Ethernet", status.network.name)
        assertEquals(12000.0, status.network.rxBps ?: 0.0, 0.001)
        assertEquals(3000.0, status.network.txBps ?: 0.0, 0.001)
        assertFalse(status.fans.available)
        assertEquals("kein Lueftersensor", status.fans.reason)
        assertFalse(status.temperatures.available)
        assertFalse(status.isEmpty)
    }

    @Test
    fun sensors_parsesDisks() {
        val status = parseSensors(fullJson)
        assertEquals(1, status.disks.size)
        val disk = status.disks.first()
        assertEquals("C:", disk.name)
        assertEquals(931.5, disk.totalGb ?: 0.0, 0.001)
        assertEquals(250.3, disk.freeGb ?: 0.0, 0.001)
        assertEquals(73.1, disk.percent ?: 0.0, 0.001)
    }

    @Test
    fun sensors_skipsDisksWithoutName() {
        val status = parseSensors("""{"disks":[{"total_gb":100.0},{"name":"D:"}]}""")
        assertEquals(1, status.disks.size)
        assertEquals("D:", status.disks.first().name)
        assertNull(status.disks.first().totalGb)
    }

    @Test
    fun sensors_missingFieldsAreUnavailable() {
        // Leeres Objekt: kein Block vorhanden -> nichts verfuegbar, kein Absturz.
        val status = parseSensors("{}")
        assertFalse(status.cpu.available)
        assertNull(status.cpu.percent)
        assertNull(status.cpu.cores)
        assertFalse(status.ram.available)
        assertFalse(status.gpu.available)
        assertFalse(status.network.available)
        assertTrue(status.disks.isEmpty())
        assertFalse(status.fans.available)
        assertFalse(status.temperatures.available)
        assertTrue(status.isEmpty)
    }

    @Test
    fun sensors_missingBlockFieldsStayNull() {
        // available true, aber kein einziger Messwert: nichts wird geschaetzt.
        val status = parseSensors(
            """{"cpu":{"available":true},"ram":{"available":true},"gpu":{"available":true}}"""
        )
        assertTrue(status.cpu.available)
        assertNull(status.cpu.percent)
        assertNull(status.cpu.cores)
        assertNull(status.ram.usedMb)
        assertNull(status.ram.percent)
        assertNull(status.gpu.percent)
        assertEquals("", status.gpu.name)
    }

    @Test
    fun sensors_availableFalseKeepsReasonOnly() {
        val status = parseSensors(
            """{"cpu":{"available":false,"percent":55.0,"reason":"kein Zugriff"},"fans":{"available":false,"reason":"kein Sensor"}}"""
        )
        assertFalse(status.cpu.available)
        // Werte eines nicht verfuegbaren Blocks werden bewusst verworfen.
        assertNull(status.cpu.percent)
        assertEquals("kein Zugriff", status.cpu.reason)
        assertFalse(status.fans.available)
        assertEquals("kein Sensor", status.fans.reason)
    }

    @Test
    fun sensors_nullValuesBecomeNull() {
        val status = parseSensors(
            """
            {"cpu":{"available":true,"percent":null,"cores":null},
             "ram":{"available":true,"used_mb":null,"total_mb":null,"percent":null},
             "gpu":{"available":true,"name":"","percent":null,
                    "memory_used_mb":null,"memory_total_mb":null},
             "network":{"available":true,"name":"WLAN","rx_bps":null,"tx_bps":null}}
            """.trimIndent()
        )
        assertTrue(status.cpu.available)
        assertNull(status.cpu.percent)
        assertNull(status.cpu.cores)
        assertNull(status.ram.usedMb)
        assertNull(status.ram.totalMb)
        assertNull(status.ram.percent)
        assertNull(status.gpu.memoryUsedMb)
        assertTrue(status.network.available)
        assertNull(status.network.rxBps)
        assertNull(status.network.txBps)
    }

    @Test
    fun sensors_blocksMayBeJsonNull() {
        val status = parseSensors("""{"cpu":null,"disks":null,"fans":null}""")
        assertFalse(status.cpu.available)
        assertTrue(status.disks.isEmpty())
        assertFalse(status.fans.available)
        assertTrue(status.isEmpty)
    }

    @Test
    fun sensors_percentIsClamped() {
        val status = parseSensors("""{"cpu":{"available":true,"percent":150.0},"ram":{"available":true,"percent":-5.0}}""")
        assertEquals(100.0, status.cpu.percent ?: 0.0, 0.001)
        assertEquals(0.0, status.ram.percent ?: 0.0, 0.001)
    }

    @Test
    fun sensors_brokenJsonGivesEmptyStatus() {
        assertTrue(parseSensors("{kaputt").isEmpty)
        assertTrue(parseSensors("kein json").isEmpty)
        assertTrue(parseSensors("").isEmpty)
        assertTrue(parseSensors("[]").isEmpty)
    }

    @Test
    fun sensors_httpErrorGivesEmptyStatus() {
        // Ein Agent ohne /sensors antwortet mit 404 - keine Fantasiewerte.
        assertTrue(parseSensors("HTTP 404: not found").isEmpty)
        assertTrue(parseSensors("HTTP 500: kaputt").isEmpty)
    }

    @Test
    fun formatters_useGermanNumbers() {
        assertEquals("12", fmtSensorPercent(12.3))
        assertEquals("100", fmtSensorPercent(120.0))
        assertEquals("931,5", fmtSensorGb(931.45))
        assertEquals("9.011", fmtSensorMb(9011L))
        assertEquals("0,1", fmtSensorRate(12000.0))
        assertEquals("0,0", fmtSensorRate(-3.0))
    }
}
