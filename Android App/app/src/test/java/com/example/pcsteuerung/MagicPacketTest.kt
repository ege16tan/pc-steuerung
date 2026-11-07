package com.example.pcsteuerung

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagicPacketTest {

    @Test
    fun buildsPacketWithCorrectLength() {
        val packet = buildMagicPacket("AA:BB:CC:DD:EE:FF")
        assertEquals(102, packet?.size)
    }

    @Test
    fun packetStartsWithSixFF() {
        val packet = buildMagicPacket("AA:BB:CC:DD:EE:FF")!!
        for (i in 0 until 6) {
            assertEquals(0xFF.toByte(), packet[i])
        }
    }

    @Test
    fun packetContainsMacSixteenTimes() {
        val packet = buildMagicPacket("AA:BB:CC:DD:EE:FF")!!
        val mac = byteArrayOf(
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        for (i in 0 until 16) {
            val expected = mac
            val actual = packet.copyOfRange(6 + i * 6, 6 + (i + 1) * 6)
            assertArrayEquals("Durchlauf $i", expected, actual)
        }
    }

    @Test
    fun acceptsDashSeparatedMac() {
        val packet = buildMagicPacket("AA-BB-CC-DD-EE-FF")
        assertEquals(102, packet?.size)
    }

    @Test
    fun returnsNullForInvalidMac() {
        assertNull(buildMagicPacket("AA:BB:CC:DD:EE"))
        assertNull(buildMagicPacket("GG:BB:CC:DD:EE:FF"))
        assertNull(buildMagicPacket(""))
    }
}
