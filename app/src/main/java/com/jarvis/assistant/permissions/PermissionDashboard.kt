package com.jarvis.assistant.permissions

import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.core.model.PermissionStatus
import com.jarvis.assistant.security.PermissionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live permission state for the dashboard. Always re-read from Android so a grant
 * revoked in system settings disappears here immediately.
 */
@Singleton
class PermissionDashboard @Inject constructor(
    private val gate: PermissionGate,
) {
    fun isGranted(key: PermissionKey): Boolean = gate.isGranted(key)

    fun statuses(): List<PermissionStatus> {
        val now = System.currentTimeMillis()
        return PermissionRegistry.descriptors.map { descriptor ->
            PermissionStatus(
                key = descriptor.key,
                granted = gate.isGranted(descriptor.key),
                lastCheckedAt = now,
            )
        }
    }

    fun missingForOnboarding(): List<PermissionKey> = PermissionRegistry.descriptors
        .filter { it.requiredForOnboarding && !gate.isGranted(it.key) }
        .map { it.key }
}
