package com.example.pcsteuerung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Pruefungen fuer die Verschluesselungslogik (AES/GCM, Versionsbyte und
 * Base64-Packung). Der Schluessel stammt hier aus dem JVM-KeyGenerator; auf
 * dem Geraet kommt er aus dem Android-Keystore - die Logik ist identisch.
 */
class CryptoFormatTest {

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun verschluesselung_laeuft_rundherum() {
        val schluessel = key()
        val text = "MeinPasswort1!Über-UTF-8-ÄÖÜ"
        val gepackt = CryptoFormat.encryptText(text, schluessel)
        assertEquals(text, CryptoFormat.decryptText(gepackt, schluessel))
    }

    @Test
    fun jeder_aufruf_erzeugt_neuen_initialvektor() {
        val schluessel = key()
        val erster = CryptoFormat.encryptText("gleiches Passwort", schluessel)
        val zweiter = CryptoFormat.encryptText("gleiches Passwort", schluessel)
        // Zufalls-IV: gepackte Werte unterscheiden sich, entschluesseln beide den Text.
        assertNotEquals(erster, zweiter)
        assertEquals("gleiches Passwort", CryptoFormat.decryptText(erster, schluessel))
        assertEquals("gleiches Passwort", CryptoFormat.decryptText(zweiter, schluessel))
    }

    @Test
    fun kaputte_pakete_bringen_null() {
        val schluessel = key()
        assertNull(CryptoFormat.decryptText("", schluessel))
        assertNull(CryptoFormat.decryptText("kein-base64!!!", schluessel))
        // Paket zu kurz:
        assertNull(
            CryptoFormat.decryptText(
                Base64.getEncoder().encodeToString(byteArrayOf(2, 1, 2, 3)),
                schluessel,
            )
        )
        // Alte Version (Klartext) darf nie durch die Entschluesselung laufen:
        assertNull(
            CryptoFormat.decryptText(
                Base64.getEncoder().encodeToString(byteArrayOf(1, 1, 2, 3)),
                schluessel,
            )
        )
    }

    @Test
    fun falscher_schluessel_bringt_null() {
        val gepackt = CryptoFormat.encryptText("geheim", key())
        assertNull(CryptoFormat.decryptText(gepackt, key()))
    }
}
