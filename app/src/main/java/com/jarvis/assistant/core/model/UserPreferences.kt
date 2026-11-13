package com.jarvis.assistant.core.model

/**
 * Two strictly separate operating modes. JARVIS never silently crosses from
 * LOCAL to GEMINI; only the user moves the switch.
 */
enum class OperatingMode {
    /** Cloud voice conversation through Gemini Live. Requires recorded consent. */
    GEMINI,

    /** No network at all. Local parser, local TTS, local actions only. */
    LOCAL,
}

/** The consent and policy state that the privacy core reads before acting. */
data class UserPreferences(
    val mode: OperatingMode = OperatingMode.LOCAL,
    val geminiConsentGrantedAt: Long? = null,
    val geminiModel: String = "gemini-3.8-live",
    val voiceName: String = "Aoede",
    val backendBaseUrl: String = "",
    /** A Gemini key is stored (encrypted) on this device. The value itself never enters this model. */
    val directApiKeyPresent: Boolean = false,
    /** When true, sessions use the on-device key instead of the JARVIS backend. */
    val useDirectApiKey: Boolean = false,
    val pauseAndConfirmHighRisk: Boolean = true,
    val autoApproveMediumRisk: Boolean = false,
    val notificationAccessConsent: Boolean = false,
    /** Packages the user has excluded from notification reading, even with consent. */
    val notificationBlockedPackages: Set<String> = emptySet(),
    val contactsConsent: Boolean = false,
    val speakRepliesInLocalMode: Boolean = true,
    val maxActionsPerMinute: Int = 20,
    val onboardingComplete: Boolean = false,
) {
    val geminiVoiceEnabled: Boolean
        get() = mode == OperatingMode.GEMINI && geminiConsentGrantedAt != null
}

/** Result of a one-off permission check surfaced in the dashboard. */
data class PermissionStatus(
    val key: PermissionKey,
    val granted: Boolean,
    val grantedAt: Long? = null,
    val lastCheckedAt: Long = System.currentTimeMillis(),
)

/** Permissions and special accesses JARVIS knows how to explain. */
enum class PermissionKey(val androidPermission: String?, val isSpecialAccess: Boolean) {
    MICROPHONE("android.permission.RECORD_AUDIO", false),
    NOTIFICATIONS("android.permission.POST_NOTIFICATIONS", false),
    NOTIFICATION_LISTENER("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", true),
    CONTACTS("android.permission.READ_CONTACTS", false),
    WRITE_SETTINGS("android.permission.WRITE_SETTINGS", true),
    DO_NOT_DISTURB("android.permission.ACCESS_NOTIFICATION_POLICY", true),
    EXACT_ALARMS("android.permission.SCHEDULE_EXACT_ALARM", true),
    ASSISTANT_ROLE("android.app.role.ASSISTANT", true),
    NETWORK("android.permission.INTERNET", false),
    BATTERY_UNRESTRICTED(null, true),
}
