package com.example.pcsteuerung

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Schnittstelle fuer Verschluesselung. Auf dem Geraet ist das der
 * Android-Keystore; fuer JVM-Unit-Tests kann ein beliebiger AES-Schluessel
 * benutzt werden, weil die Packungs-Logik identisch ist.
 */
interface SecretCodec {
    /** Verschluesselt Klartext zu einem uebertragbaren Paket (Base64). */
    fun encrypt(plain: String): String

    /** Entschluesselt ein Paket; null, wenn Schluessel/Format nicht passen. */
    fun decrypt(packed: String): String?
}

/**
 * Packungs-Logik fuer AES/GCM (schluesselunabhaengig, daher testbar).
 *
 * Format (Base64): [1 Byte Version = 2][12 Bytes Initialvektor][Geheimtext + 16-Byte-Tag].
 * Der Zufalls-Initialvektor kommt vom Cipher selbst, damit jeder Aufruf ein
 * einzigartiges Paket erzeugt. Version 1 waere der alte Klartext-Speicher.
 */
object CryptoFormat {
    private const val VERSION = 2
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun encryptText(plain: String, secret: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val iv = cipher.iv ?: ByteArray(IV_LENGTH)
        val secretBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + secretBytes.size)
        packed[0] = VERSION.toByte()
        iv.copyInto(packed, 1)
        secretBytes.copyInto(packed, 1 + iv.size)
        return Base64.getEncoder().encodeToString(packed)
    }

    fun decryptText(packed: String, secret: SecretKey): String? = try {
        val raw = Base64.getDecoder().decode(packed)
        if (raw.size < 1 + IV_LENGTH || raw[0].toInt() != VERSION) {
            null
        } else {
            val iv = raw.copyOfRange(1, 1 + IV_LENGTH)
            val secretBytes = raw.copyOfRange(1 + IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(secretBytes), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // Kaputtes Paket, falscher Schluessel oder Manipulation: sauber null.
        null
    }
}

/**
 * Verschluesselung mit einem AES-256-GCM-Schluessel im Android-Keystore:
 * der Schluessel bleibt im Keystore und ist nicht exportierbar. Der
 * Datenbestand (Profil-Passwoerter) liegt nur noch verschluesselt vor; ein
 * Ruecksetzen auf Werkseinstellungen loescht Schluessel und Daten bewusst
 * zusammen.
 */
object KeystoreCodec : SecretCodec {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pc-steuerung-profile"
    private const val KEY_BITS = 256

    override fun encrypt(plain: String): String =
        CryptoFormat.encryptText(plain, key())

    override fun decrypt(packed: String): String? = try {
        CryptoFormat.decryptText(packed, key())
    } catch (e: Exception) {
        null
    }

    /** Holt den Keystore-Schluessel oder erzeugt ihn beim ersten Mal neu. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }
}
