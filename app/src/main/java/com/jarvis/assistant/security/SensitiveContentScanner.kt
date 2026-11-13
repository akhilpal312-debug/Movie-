package com.jarvis.assistant.security

/** Outcome of scanning text that is about to be sent to a cloud model. */
data class CloudPayloadScan(
    val safeText: String,
    val redactions: List<RedactionKind>,
    /** True when the text must not be sent at all (passwords, one-time codes). */
    val blocked: Boolean,
) {
    val clean: Boolean get() = redactions.isEmpty() && !blocked
}

enum class RedactionKind(val label: String) {
    ONE_TIME_CODE("one-time code"),
    CARD_NUMBER("card number"),
    AADHAAR_LIKE("national ID number"),
    EMAIL("email address"),
    PHONE_NUMBER("phone number"),
    PASSWORD_STATEMENT("password or secret"),
    BANK_ACCOUNT("account number"),
}

/**
 * Last line of defence before anything travels to Gemini.
 *
 * JARVIS already refuses to attach device content to a request, but a user can
 * still say a secret out loud, or a notification could be pasted in. Anything that
 * looks like a credential is stripped; explicit password/OTP statements block the
 * send entirely instead of being silently sent.
 */
class SensitiveContentScanner {

    fun scan(text: String): CloudPayloadScan {
        var output = text
        val found = mutableListOf<RedactionKind>()
        var blocked = false

        if (PASSWORD_STATEMENT.containsMatchIn(text)) {
            found += RedactionKind.PASSWORD_STATEMENT
            blocked = true
        }

        for ((pattern, kind) in PATTERNS) {
            val matches = pattern.findAll(output).toList()
            if (matches.isNotEmpty()) {
                found += kind
                output = pattern.replace(output) { "[" + kind.label + " redacted]" }
            }
        }

        // A bare 4-8 digit number right after the word "code" is almost certainly an OTP.
        if (OTP_CONTEXT.containsMatchIn(text)) {
            if (found.none { it == RedactionKind.ONE_TIME_CODE }) found += RedactionKind.ONE_TIME_CODE
            blocked = true
            output = OTP_CONTEXT.replace(output) { match ->
                match.value.replace(Regex("\\b\\d{4,8}\\b"), "[one-time code redacted]")
            }
        }

        return CloudPayloadScan(
            safeText = output,
            redactions = found.distinct(),
            blocked = blocked,
        )
    }

    private companion object {
        val PASSWORD_STATEMENT = Regex(
            "(?i)\\b(password|passcode|pin|passphrase|otp|verification code|one[- ]time code|cvv|seed phrase)\\b\\s*(is|:|=)?\\s*\\S+",
        )
        val OTP_CONTEXT = Regex(
            "(?i)\\b(code|otp)\\b\\D{0,12}\\d{4,8}\\b",
        )
        val PATTERNS: List<Pair<Regex, RedactionKind>> = listOf(
            Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b") to RedactionKind.CARD_NUMBER,
            Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b") to RedactionKind.AADHAAR_LIKE,
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to RedactionKind.EMAIL,
            Regex("(\\+\\d{1,3}[ -]?)?\\b\\d{10}\\b") to RedactionKind.PHONE_NUMBER,
        )
    }
}
