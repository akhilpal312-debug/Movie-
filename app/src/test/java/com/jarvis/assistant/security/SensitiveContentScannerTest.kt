package com.jarvis.assistant.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveContentScannerTest {

    private val scanner = SensitiveContentScanner()

    @Test
    fun `blocks a one-time code and redacts the digits`() {
        val scan = scanner.scan("my verification code is 482913, please remember it")

        assertThat(scan.blocked).isTrue()
        assertThat(scan.safeText).doesNotContain("482913")
        assertThat(scan.redactions).contains(RedactionKind.ONE_TIME_CODE)
    }

    @Test
    fun `blocks password statements`() {
        val scan = scanner.scan("my password is hunter2")

        assertThat(scan.blocked).isTrue()
        assertThat(scan.redactions).contains(RedactionKind.PASSWORD_STATEMENT)
    }

    @Test
    fun `redacts a card number without blocking the turn`() {
        val scan = scanner.scan("pay with 4111 1111 1111 1111 tomorrow")

        assertThat(scan.blocked).isFalse()
        assertThat(scan.safeText).doesNotContain("4111")
        assertThat(scan.redactions).contains(RedactionKind.CARD_NUMBER)
    }

    @Test
    fun `redacts emails and phone numbers`() {
        val scan = scanner.scan("mail me at asha@example.com or call 9876543210")

        assertThat(scan.redactions).containsAtLeast(RedactionKind.EMAIL, RedactionKind.PHONE_NUMBER)
        assertThat(scan.safeText).doesNotContain("asha@example.com")
        assertThat(scan.safeText).doesNotContain("9876543210")
    }

    @Test
    fun `leaves ordinary speech untouched`() {
        val text = "set an alarm for seven thirty and open the camera"
        val scan = scanner.scan(text)

        assertThat(scan.blocked).isFalse()
        assertThat(scan.clean).isTrue()
        assertThat(scan.safeText).isEqualTo(text)
    }

    @Test
    fun `handles hinglish commands without false positives`() {
        val text = "kal subah 7 baje ka alarm lagao"
        val scan = scanner.scan(text)

        assertThat(scan.blocked).isFalse()
        assertThat(scan.safeText).isEqualTo(text)
    }
}
