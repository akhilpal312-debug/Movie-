package com.jarvis.assistant.security

import com.google.common.truth.Truth.assertThat
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.AudioStream
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.core.model.UserPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JarvisPrivacyCoreTest {

    private class FakeGate(private val granted: Set<PermissionKey> = PermissionKey.entries.toSet()) :
        PermissionGate {
        override fun isGranted(key: PermissionKey): Boolean = key in granted
    }

    private class RecordingAudit : AuditSink {
        val records = mutableListOf<AuditRecord>()
        override suspend fun record(record: AuditRecord) {
            records += record
        }
    }

    private fun prefs(
        mode: OperatingMode = OperatingMode.GEMINI,
        notificationConsent: Boolean = false,
        contactsConsent: Boolean = false,
        autoMedium: Boolean = false,
        maxActions: Int = 100,
    ) = UserPreferences(
        mode = mode,
        geminiConsentGrantedAt = 1L,
        notificationAccessConsent = notificationConsent,
        contactsConsent = contactsConsent,
        autoApproveMediumRisk = autoMedium,
        maxActionsPerMinute = maxActions,
    )

    private fun core(audit: AuditSink = NoopAuditSink, clock: () -> Long = { 1_000L }) =
        JarvisPrivacyCore(auditSink = audit, clock = clock)

    @Test
    fun `low risk action from voice is allowed`() = runTest {
        val decision = core().authorize(
            ActionRequest(JarvisAction.SetVolume(AudioStream.MUSIC, 30), ActionSource.VOICE_GEMINI),
            prefs(),
            FakeGate(),
        )
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `high risk action needs confirmation and only runs with the matching fingerprint`() = runTest {
        val call = JarvisAction.CallContact("Mom")
        val fingerprint = ActionSummary.fingerprint(call)
        val policy = core()

        val first = policy.authorize(
            ActionRequest(call, ActionSource.VOICE_GEMINI),
            prefs(contactsConsent = true),
            FakeGate(),
        )
        assertThat(first).isInstanceOf(PolicyDecision.Confirm::class.java)
        assertThat((first as PolicyDecision.Confirm).fingerprint).isEqualTo(fingerprint)

        val confirmed = policy.authorize(
            ActionRequest(call, ActionSource.VOICE_GEMINI, confirmedFingerprint = fingerprint),
            prefs(contactsConsent = true),
            FakeGate(),
        )
        assertThat(confirmed).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `approving one action cannot authorise a different one`() = runTest {
        val approved = ActionSummary.fingerprint(JarvisAction.CallContact("Mom"))
        val different = JarvisAction.ComposeMessage("Mom", "I will be late")

        val decision = core().authorize(
            ActionRequest(different, ActionSource.VOICE_GEMINI, confirmedFingerprint = approved),
            prefs(contactsConsent = true),
            FakeGate(),
        )
        assertThat(decision).isInstanceOf(PolicyDecision.Confirm::class.java)
    }

    @Test
    fun `cloud actions are refused in local mode`() = runTest {
        val decision = core().authorize(
            ActionRequest(JarvisAction.OpenApp("camera"), ActionSource.VOICE_GEMINI),
            prefs(mode = OperatingMode.LOCAL),
            FakeGate(),
        )
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
        assertThat((decision as PolicyDecision.Deny).code).isEqualTo(PolicyCode.DENIED_MODE)
    }

    @Test
    fun `local parser actions are allowed in local mode`() = runTest {
        val decision = core().authorize(
            ActionRequest(JarvisAction.OpenApp("camera"), ActionSource.LOCAL_PARSER),
            prefs(mode = OperatingMode.LOCAL),
            FakeGate(),
        )
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `automation may never perform high risk work`() = runTest {
        val decision = core().authorize(
            ActionRequest(JarvisAction.ComposeMessage("Mom", "hello"), ActionSource.AUTOMATION),
            prefs(contactsConsent = true),
            FakeGate(),
        )
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
        assertThat((decision as PolicyDecision.Deny).code).isEqualTo(PolicyCode.DENIED_AUTOMATION)
    }

    @Test
    fun `automation blocks medium risk unless the user opted in`() = runTest {
        val brightness = JarvisAction.SetBrightness(40)
        val denied = core().authorize(
            ActionRequest(brightness, ActionSource.AUTOMATION),
            prefs(autoMedium = false),
            FakeGate(),
        )
        assertThat(denied).isInstanceOf(PolicyDecision.Deny::class.java)

        val allowed = core().authorize(
            ActionRequest(brightness, ActionSource.AUTOMATION),
            prefs(autoMedium = true),
            FakeGate(),
        )
        assertThat(allowed).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `automation may run low risk routines`() = runTest {
        val decision = core().authorize(
            ActionRequest(JarvisAction.DeviceInfo(com.jarvis.assistant.core.model.DeviceInfoField.BATTERY), ActionSource.AUTOMATION),
            prefs(),
            FakeGate(),
        )
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `notifications require recorded consent before access`() = runTest {
        val action = JarvisAction.ReadNotifications(3)
        val noConsent = core().authorize(
            ActionRequest(action, ActionSource.VOICE_GEMINI),
            prefs(notificationConsent = false),
            FakeGate(),
        )
        assertThat((noConsent as PolicyDecision.Deny).code).isEqualTo(PolicyCode.DENIED_CONSENT)

        val noPermission = core().authorize(
            ActionRequest(action, ActionSource.VOICE_GEMINI),
            prefs(notificationConsent = true),
            FakeGate(setOf(PermissionKey.MICROPHONE)),
        )
        assertThat((noPermission as PolicyDecision.Deny).code).isEqualTo(PolicyCode.DENIED_PERMISSION)
    }

    @Test
    fun `secrets are refused before anything else runs`() = runTest {
        val decision = core().authorize(
            ActionRequest(JarvisAction.Remember("my password is hunter2"), ActionSource.VOICE_GEMINI),
            prefs(),
            FakeGate(),
        )
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
        assertThat((decision as PolicyDecision.Deny).code).isEqualTo(PolicyCode.DENIED_SENSITIVE_CONTENT)
    }

    @Test
    fun `action rate limiting stops a runaway loop`() = runTest {
        var now = 1_000_000L
        val policy = core(clock = { now })
        val gate = FakeGate()
        val quota = prefs(maxActions = 2)

        repeat(2) {
            val decision = policy.authorize(
                ActionRequest(JarvisAction.DeviceInfo(com.jarvis.assistant.core.model.DeviceInfoField.BATTERY), ActionSource.AUTOMATION),
                quota,
                gate,
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }

        val blocked = policy.authorize(
            ActionRequest(JarvisAction.DeviceInfo(com.jarvis.assistant.core.model.DeviceInfoField.BATTERY), ActionSource.AUTOMATION),
            quota,
            gate,
        )
        assertThat((blocked as PolicyDecision.Deny).code).isEqualTo(PolicyCode.DENIED_RATE_LIMIT)

        now += 61_000
        val recovered = policy.authorize(
            ActionRequest(JarvisAction.DeviceInfo(com.jarvis.assistant.core.model.DeviceInfoField.BATTERY), ActionSource.AUTOMATION),
            quota,
            gate,
        )
        assertThat(recovered).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `audit records allow confirm and deny`() = runTest {
        val audit = RecordingAudit()
        val policy = core(audit)
        val gate = FakeGate()

        policy.authorize(
            ActionRequest(JarvisAction.MediaControl(com.jarvis.assistant.core.model.MediaCommand.TOGGLE), ActionSource.VOICE_GEMINI),
            prefs(),
            gate,
        )
        policy.authorize(
            ActionRequest(JarvisAction.CallContact("Mom"), ActionSource.VOICE_GEMINI),
            prefs(contactsConsent = true),
            gate,
        )
        policy.authorize(
            ActionRequest(JarvisAction.OpenApp("camera"), ActionSource.VOICE_GEMINI),
            prefs(mode = OperatingMode.LOCAL),
            gate,
        )

        assertThat(audit.records.map { it.decision })
            .containsExactly("ALLOW", "CONFIRM", "DENY:DENIED_MODE")
            .inOrder()
    }

    @Test
    fun `confirming a medium risk change makes it run`() = runTest {
        val brightness = JarvisAction.SetBrightness(50)
        val fingerprint = ActionSummary.fingerprint(brightness)

        val decision = core().authorize(
            ActionRequest(brightness, ActionSource.VOICE_GEMINI, confirmedFingerprint = fingerprint),
            prefs(),
            FakeGate(),
        )
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }
}
