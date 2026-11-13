package com.jarvis.assistant.security

import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.core.model.RiskLevel
import com.jarvis.assistant.core.model.UserPreferences

/**
 * JARVIS PRIVACY CORE.
 *
 * Every AI-originated or automated request passes through [authorize] before any
 * Android API runs. The rules are ordered from hardest to softest so that a failure
 * anywhere still leaves the device safe:
 *
 *  1. Mode and consent gates.
 *  2. Never-when-automatic rules.
 *  3. Secret detection (blocks the request outright).
 *  4. Required permission / special access.
 *  5. Risk-based confirmation, bound to an exact action fingerprint.
 *  6. Rate limiting.
 *
 * The core has no Android dependencies, which is what makes its rules testable.
 */
class JarvisPrivacyCore(
    private val auditSink: AuditSink = NoopAuditSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val rateLimiter = ActionRateLimiter(clock)
    private val scanner = SensitiveContentScanner()

    /**
     * Authorise or refuse a request.
     *
     * @param gate reports Android permission and special-access state.
     */
    suspend fun authorize(
        request: ActionRequest,
        preferences: UserPreferences,
        gate: PermissionGate,
    ): PolicyDecision {
        val action = request.action
        val decision = decide(request, preferences, gate)
        auditSink.record(
            AuditRecord(
                at = clock(),
                actionType = action.type,
                source = request.source,
                decision = when (decision) {
                    is PolicyDecision.Allow -> "ALLOW"
                    is PolicyDecision.Confirm -> "CONFIRM"
                    is PolicyDecision.Deny -> "DENY:${decision.code.name}"
                },
                detail = when (decision) {
                    is PolicyDecision.Allow -> ActionSummary.describe(action)
                    is PolicyDecision.Confirm -> decision.reason
                    is PolicyDecision.Deny -> decision.reason
                },
            ),
        )
        return decision
    }

    private fun decide(
        request: ActionRequest,
        preferences: UserPreferences,
        gate: PermissionGate,
    ): PolicyDecision {
        val action = request.action
        val fingerprint = ActionSummary.fingerprint(action)

        // 1. A model must never act while the user is in Local Privacy mode. This is
        //    defence in depth against a stale session that was still streaming.
        if (preferences.mode == OperatingMode.LOCAL &&
            (request.source == ActionSource.VOICE_GEMINI || request.source == ActionSource.TEXT_GEMINI)
        ) {
            return PolicyDecision.Deny(
                PolicyCode.DENIED_MODE,
                "Local Privacy mode does not accept commands from the cloud model.",
            )
        }

        // 2. Actions that touch private data need explicit, recorded consent.
        when (action.type) {
            ActionType.READ_NOTIFICATIONS -> if (!preferences.notificationAccessConsent) {
                return PolicyDecision.Deny(
                    PolicyCode.DENIED_CONSENT,
                    "Notification access has not been authorised.",
                )
            }
            ActionType.FIND_CONTACT, ActionType.CALL_CONTACT, ActionType.COMPOSE_MESSAGE ->
                if (!preferences.contactsConsent) {
                    return PolicyDecision.Deny(
                        PolicyCode.DENIED_CONSENT,
                        "Contact access has not been authorised.",
                    )
                }
            ActionType.REMEMBER -> {
                val scan = scanner.scan((action as JarvisAction.Remember).text)
                if (scan.blocked) return sensitiveDenial()
            }
            else -> Unit
        }

        // 3. Secrets never travel and are never acted upon.
        val spokenText = textContentOf(action)
        if (spokenText != null) {
            val scan = scanner.scan(spokenText)
            if (scan.blocked) return sensitiveDenial()
        }

        // 4. Automation can only ever do LOW risk work, and MEDIUM only when the user
        //    has switched on auto-approval for it. HIGH risk is never automated.
        if (request.source == ActionSource.AUTOMATION) {
            when (action.riskLevel) {
                RiskLevel.HIGH -> return PolicyDecision.Deny(
                    PolicyCode.DENIED_AUTOMATION,
                    "Automations may never perform ${action.type.wire.lowercase().replace('_', ' ')}.",
                )
                RiskLevel.MEDIUM -> if (!preferences.autoApproveMediumRisk) {
                    return PolicyDecision.Deny(
                        PolicyCode.DENIED_AUTOMATION,
                        "Automations may only change this when you enable medium-risk auto-approval.",
                    )
                }
                RiskLevel.LOW -> Unit
            }
        }

        // 5. Required permissions and special access.
        requiredPermission(action)?.let { key ->
            if (!gate.isGranted(key)) {
                return PolicyDecision.Deny(
                    PolicyCode.DENIED_PERMISSION,
                    "JARVIS does not have ${key.name.lowercase().replace('_', ' ')} access yet.",
                )
            }
        }

        // 6. Risk gate. A HIGH risk action only runs with a matching fingerprint.
        if (action.riskLevel == RiskLevel.HIGH || requiresAlwaysConfirm(action)) {
            if (request.confirmedFingerprint != fingerprint) {
                return PolicyDecision.Confirm(
                    reason = confirmationReason(action),
                    preview = ActionSummary.describe(action),
                    fingerprint = fingerprint,
                )
            }
        } else if (action.riskLevel == RiskLevel.MEDIUM && !preferences.autoApproveMediumRisk &&
            preferences.pauseAndConfirmHighRisk && request.source != ActionSource.UI
        ) {
            if (request.confirmedFingerprint != fingerprint) {
                return PolicyDecision.Confirm(
                    reason = "This changes your device settings.",
                    preview = ActionSummary.describe(action),
                    fingerprint = fingerprint,
                )
            }
        }

        // 7. Rate limiting applies last, so denials above stay deterministic.
        if (!rateLimiter.tryAcquire(preferences.maxActionsPerMinute)) {
            return PolicyDecision.Deny(
                PolicyCode.DENIED_RATE_LIMIT,
                "Too many actions in one minute. Pausing for your safety.",
            )
        }

        return PolicyDecision.Allow
    }

    /** Permission that must already be granted for the action to be attempted. */
    fun requiredPermission(action: JarvisAction): PermissionKey? = when (action.type) {
        ActionType.SET_BRIGHTNESS -> PermissionKey.WRITE_SETTINGS
        ActionType.SET_DND -> PermissionKey.DO_NOT_DISTURB
        ActionType.READ_NOTIFICATIONS -> PermissionKey.NOTIFICATION_LISTENER
        ActionType.FIND_CONTACT, ActionType.CALL_CONTACT, ActionType.COMPOSE_MESSAGE -> PermissionKey.CONTACTS
        ActionType.CREATE_REMINDER -> PermissionKey.EXACT_ALARMS
        else -> null
    }

    /** Read/draft pairs that are never automatic even at MEDIUM risk. */
    private fun requiresAlwaysConfirm(action: JarvisAction): Boolean = when (action.type) {
        ActionType.READ_NOTIFICATIONS, ActionType.CALL_CONTACT, ActionType.COMPOSE_MESSAGE -> true
        else -> false
    }

    private fun confirmationReason(action: JarvisAction): String = when (action.type) {
        ActionType.CALL_CONTACT -> "JARVIS will open the dialer for this contact. It never places the call itself."
        ActionType.COMPOSE_MESSAGE -> "JARVIS will draft the message and open your messaging app. Nothing is sent automatically."
        ActionType.READ_NOTIFICATIONS -> "JARVIS will read recent notifications on this device. They stay on device and are never uploaded."
        else -> "This action needs your explicit approval."
    }

    /** The user-visible textual content of an action, if any. */
    private fun textContentOf(action: JarvisAction): String? = when (action) {
        is JarvisAction.Remember -> action.text
        is JarvisAction.ComposeMessage -> action.body
        is JarvisAction.CreateReminder -> action.message
        is JarvisAction.AddTask -> action.title
        is JarvisAction.Recall -> action.query
        is JarvisAction.FindContact -> action.query
        is JarvisAction.CallContact -> action.contactQuery
        is JarvisAction.OpenApp -> action.appQuery
        is JarvisAction.CreateRoutine -> action.steps.joinToString(" ") + " " + action.name
        is JarvisAction.RunRoutine -> action.name
        is JarvisAction.CompleteTask -> action.query
        else -> null
    }

    private fun sensitiveDenial() = PolicyDecision.Deny(
        PolicyCode.DENIED_SENSITIVE_CONTENT,
        "That looks like a password, PIN or one-time code. JARVIS does not handle secrets — please keep it out of the assistant.",
    )
}
