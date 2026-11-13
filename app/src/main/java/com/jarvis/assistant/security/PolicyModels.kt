package com.jarvis.assistant.security

import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.PermissionKey

/** A single request to do something, before any Android API is touched. */
data class ActionRequest(
    val action: JarvisAction,
    val source: ActionSource,
    /**
     * Fingerprint of the action the user actually approved. Supplied only after the
     * confirmation sheet is accepted, so approving action A can never run action B.
     */
    val confirmedFingerprint: String? = null,
)

enum class PolicyCode {
    ALLOWED,
    NEEDS_CONFIRMATION,
    DENIED_MODE,
    DENIED_CONSENT,
    DENIED_SENSITIVE_CONTENT,
    DENIED_PERMISSION,
    DENIED_RATE_LIMIT,
    DENIED_AUTOMATION,
    DENIED_UNSUPPORTED,
}

sealed interface PolicyDecision {
    data object Allow : PolicyDecision

    data class Confirm(val reason: String, val preview: String, val fingerprint: String) : PolicyDecision

    data class Deny(val code: PolicyCode, val reason: String) : PolicyDecision
}

/** Lets the privacy core ask about Android permissions without depending on it. */
interface PermissionGate {
    fun isGranted(key: PermissionKey): Boolean
}

/** Audit trail of every policy decision, stored locally and never uploaded. */
data class AuditRecord(
    val at: Long,
    val actionType: ActionType,
    val source: ActionSource,
    val decision: String,
    val detail: String,
)

interface AuditSink {
    suspend fun record(record: AuditRecord)
}

object NoopAuditSink : AuditSink {
    override suspend fun record(record: AuditRecord) = Unit
}
