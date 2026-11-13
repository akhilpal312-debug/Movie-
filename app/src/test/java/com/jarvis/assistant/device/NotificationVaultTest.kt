package com.jarvis.assistant.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationVaultTest {

    private fun notification(pkg: String = "com.example.chat", label: String = "Chat") =
        LocalNotification(
            packageName = pkg,
            appLabel = label,
            title = "Asha",
            text = "see you at 6",
            postedAt = 1L,
        )

    @Test
    fun `stores nothing until the listener is connected and consent is recorded`() {
        val vault = NotificationVault()
        vault.add(notification())
        assertThat(vault.recent(5)).isEmpty()

        vault.enabled = true
        vault.add(notification())
        assertThat(vault.recent(5)).isEmpty()

        vault.consentGranted = true
        vault.add(notification())
        assertThat(vault.recent(5)).hasSize(1)
    }

    @Test
    fun `drops notifications from apps the user excluded`() {
        val vault = NotificationVault().apply {
            enabled = true
            consentGranted = true
            blockedPackages = setOf("com.example.bank")
        }

        vault.add(notification(pkg = "com.example.bank", label = "Bank"))
        vault.add(notification(pkg = "com.example.chat", label = "Chat"))

        val stored = vault.recent(5)
        assertThat(stored).hasSize(1)
        assertThat(stored.single().packageName).isEqualTo("com.example.chat")
    }

    @Test
    fun `keeps the newest notifications and a bounded buffer`() {
        val vault = NotificationVault().apply {
            enabled = true
            consentGranted = true
        }
        repeat(30) { index ->
            vault.add(notification(pkg = "app$index", label = "App $index"))
        }

        val stored = vault.recent(50)
        assertThat(stored).hasSize(20)
        assertThat(stored.first().packageName).isEqualTo("app29")
    }

    @Test
    fun `clear removes buffered contents`() {
        val vault = NotificationVault().apply {
            enabled = true
            consentGranted = true
        }
        vault.add(notification())
        vault.clear()
        assertThat(vault.recent(5)).isEmpty()
    }

    @Test
    fun `recent apps are deduplicated for the selection UI`() {
        val vault = NotificationVault().apply {
            enabled = true
            consentGranted = true
        }
        vault.add(notification(pkg = "com.example.chat", label = "Chat"))
        vault.add(notification(pkg = "com.example.chat", label = "Chat"))
        vault.add(notification(pkg = "com.example.mail", label = "Mail"))

        assertThat(vault.recentApps()).containsExactly(
            "com.example.chat" to "Chat",
            "com.example.mail" to "Mail",
        )
    }
}
