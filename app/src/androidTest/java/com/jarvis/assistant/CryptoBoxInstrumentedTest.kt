package com.jarvis.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.jarvis.assistant.security.CryptoBox
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android Keystore, which cannot be tested on the JVM.
 *
 * Requires a device or emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class CryptoBoxInstrumentedTest {

    private val crypto = CryptoBox()

    @Test
    fun encryptsAndDecryptsThroughTheKeystore() {
        val plain = "buy filter coffee"
        val encrypted = crypto.encrypt(plain)

        assertThat(encrypted).isNotEqualTo(plain)
        assertThat(crypto.decrypt(encrypted)).isEqualTo(plain)
    }

    @Test
    fun producesADifferentCiphertextEachTime() {
        val first = crypto.encrypt("same text")
        val second = crypto.encrypt("same text")

        // A fresh IV per encryption is what makes GCM safe here.
        assertThat(first).isNotEqualTo(second)
        assertThat(crypto.decrypt(first)).isEqualTo("same text")
        assertThat(crypto.decrypt(second)).isEqualTo("same text")
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val encrypted = crypto.encrypt("task: pay rent")
        val tampered = encrypted.dropLast(4) + "AAAA"

        assertThat(crypto.decrypt(tampered)).isNull()
    }
}
