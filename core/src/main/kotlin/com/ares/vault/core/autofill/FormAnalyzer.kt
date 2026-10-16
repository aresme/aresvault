package com.ares.vault.core.autofill

/** OTP = a one-time-code (2FA) field and nothing else to fill. */
enum class FormType { LOGIN, SIGN_UP, OTP, NONE }

/** Result of analysing one screen/page. [T] is the platform field id (AutofillId on Android). */
data class FormAnalysis<T>(
    val type: FormType,
    val username: T? = null,
    val password: T? = null,
    val newPasswords: List<T> = emptyList(),
    val confirmPasswords: List<T> = emptyList(),
    val otp: T? = null,
) {
    /** Every field AresVault might fill, in order. */
    val allIds: List<T> get() = listOfNotNull(username, password) + newPasswords + confirmPasswords + listOfNotNull(otp)
}

object FormAnalyzer {
    private val SIGN_UP_WORDS = listOf(
        "sign up", "signup", "sign-up", "register", "registration", "create account", "create an account",
        "join", "get started", "jisajili", "fungua akaunti", "unda akaunti",
    )

    /**
     * @param fields in traversal (screen) order.
     * @param pageText visible non-input texts (titles, buttons) used for sign-up keywords.
     */
    fun <T> analyze(fields: List<Pair<T, FieldInfo>>, pageText: String = ""): FormAnalysis<T> {
        val typed = fields.map { (id, info) -> Triple(id, info, FieldClassifier.classify(info)) }
            .filter { it.third != FieldType.IGNORE }
        val passwords = typed.filter { it.third in PASSWORD_TYPES }
        val otpOnly = typed.isNotEmpty() && typed.all { it.third == FieldType.OTP }
        if (otpOnly) return FormAnalysis(FormType.OTP, otp = typed.first().first)

        val usernameField = pickUsername(typed, passwords.firstOrNull()?.first)

        if (passwords.isEmpty()) {
            // Username-only step of a multi-step login ("Next" then password).
            return if (usernameField != null) FormAnalysis(FormType.LOGIN, username = usernameField) else FormAnalysis(FormType.NONE)
        }

        val text = pageText.lowercase()
        val explicitNew = passwords.any { it.third == FieldType.NEW_PASSWORD || it.third == FieldType.CONFIRM_PASSWORD }
        val isSignUp = explicitNew || passwords.size >= 2 || SIGN_UP_WORDS.any { it in text }

        if (!isSignUp) {
            return FormAnalysis(FormType.LOGIN, username = usernameField, password = passwords.first().first)
        }
        // Sign-up: first password is the new one, any later ones (or explicit confirm) confirm it.
        val confirm = passwords.filter { it.third == FieldType.CONFIRM_PASSWORD }.map { it.first }
        val newOnes = passwords.map { it.first }.filter { it !in confirm }
        val (primary, extra) = if (newOnes.isEmpty()) listOf(confirm.first()) to confirm.drop(1) else newOnes.take(1) to newOnes.drop(1)
        return FormAnalysis(
            FormType.SIGN_UP,
            username = usernameField,
            newPasswords = primary,
            confirmPasswords = extra + confirm.filter { it !in primary },
        )
    }

    /** Typed username field before the password; else the last text input before it. */
    private fun <T> pickUsername(typed: List<Triple<T, FieldInfo, FieldType>>, firstPassword: T?): T? {
        val beforePassword = if (firstPassword == null) typed else typed.takeWhile { it.first != firstPassword }
        beforePassword.lastOrNull { it.third == FieldType.USERNAME }?.let { return it.first }
        if (firstPassword == null) return null
        return beforePassword.lastOrNull { it.third == FieldType.UNKNOWN && it.second.isTextInput }?.first
    }

    private val PASSWORD_TYPES = setOf(FieldType.PASSWORD, FieldType.NEW_PASSWORD, FieldType.CONFIRM_PASSWORD)
}
