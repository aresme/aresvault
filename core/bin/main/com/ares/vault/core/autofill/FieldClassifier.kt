package com.ares.vault.core.autofill

/**
 * Platform-independent description of one view in an autofill request, extracted from
 * AssistStructure.ViewNode. The *value* the user typed is deliberately not part of it.
 */
data class FieldInfo(
    val autofillHints: List<String> = emptyList(),
    val htmlTag: String? = null,
    /** Lowercased attribute names → values (type, name, id, autocomplete, placeholder, label…). */
    val htmlAttributes: Map<String, String> = emptyMap(),
    val inputType: Int = 0,
    val idEntry: String? = null,
    val hint: String? = null,
    val className: String? = null,
    val visible: Boolean = true,
    val focusable: Boolean = true,
    val focused: Boolean = false,
    /** True for editable text inputs (EditText, html input of a text-like type). */
    val isTextInput: Boolean = false,
)

enum class FieldType { USERNAME, PASSWORD, NEW_PASSWORD, CONFIRM_PASSWORD, OTP, IGNORE, UNKNOWN }

/**
 * Classifies a single field, by priority (spec §6.3):
 * 1. autofillHints  2. html attributes  3. inputType flags  4. id/hint/name keywords.
 */
object FieldClassifier {

    // android.text.InputType constants (duplicated so this stays JVM-pure).
    private const val CLASS_MASK = 0x0000000f
    private const val VARIATION_MASK = 0x00000ff0
    private const val CLASS_TEXT = 1
    private const val CLASS_NUMBER = 2
    private const val VAR_EMAIL = 0x20
    private const val VAR_PASSWORD = 0x80
    private const val VAR_VISIBLE_PASSWORD = 0x90
    private const val VAR_WEB_EMAIL = 0xd0
    private const val VAR_WEB_PASSWORD = 0xe0
    private const val NUM_VAR_PASSWORD = 0x10

    private val OTP_WORDS = listOf("otp", "one-time", "onetime", "one_time", "2fa", "totp", "mfa", "verification", "verify", "auth_code", "authcode", "sms", "msimbo")
    private val SKIP_WORDS = listOf("search", "query", "captcha", "coupon", "promo", "card", "cvv", "cvc", "iban", "zip", "postal", "tafuta")
    private val PASSWORD_WORDS = listOf("password", "passwd", "pass", "pwd", "passcode", "nenosiri", "neno la siri")
    private val PIN_WORD = Regex("(^|[^a-z])pin([^a-z]|$)")
    private val USERNAME_WORDS = listOf(
        "user", "username", "login", "email", "e-mail", "mail", "account", "phone", "mobile", "msisdn",
        "jina la mtumiaji", "barua pepe", "simu",
    )
    private val CONFIRM_WORDS = listOf("confirm", "repeat", "retype", "re-enter", "reenter", "again", "verify_pass", "password2", "thibitisha", "rudia")
    private val NEW_WORDS = listOf("new", "create", "choose", "mpya", "unda")

    fun classify(f: FieldInfo): FieldType {
        if (!f.visible || !f.focusable) return FieldType.IGNORE
        fromHints(f.autofillHints)?.let { return it }
        fromHtml(f)?.let { return it }

        val text = keywords(f)
        if (OTP_WORDS.any { it in text }) return FieldType.OTP
        if (SKIP_WORDS.any { it in text }) return FieldType.IGNORE

        val cls = f.inputType and CLASS_MASK
        val variation = f.inputType and VARIATION_MASK
        val isPasswordInput = (cls == CLASS_TEXT && variation in setOf(VAR_PASSWORD, VAR_VISIBLE_PASSWORD, VAR_WEB_PASSWORD)) ||
            (cls == CLASS_NUMBER && variation == NUM_VAR_PASSWORD) ||
            f.htmlAttributes["type"] == "password"
        if (isPasswordInput || PASSWORD_WORDS.any { it in text } || PIN_WORD.containsMatchIn(text)) {
            return passwordKind(text)
        }
        if (cls == CLASS_TEXT && (variation == VAR_EMAIL || variation == VAR_WEB_EMAIL)) return FieldType.USERNAME
        if (USERNAME_WORDS.any { it in text }) return FieldType.USERNAME
        return FieldType.UNKNOWN
    }

    private fun passwordKind(text: String): FieldType = when {
        CONFIRM_WORDS.any { it in text } -> FieldType.CONFIRM_PASSWORD
        NEW_WORDS.any { it in text } -> FieldType.NEW_PASSWORD
        else -> FieldType.PASSWORD
    }

    private fun fromHints(hints: List<String>): FieldType? {
        val h = hints.map { it.lowercase() }
        return when {
            h.any { it == "newpassword" || it == "new-password" } -> FieldType.NEW_PASSWORD
            h.any { it == "password" || it == "current-password" } -> FieldType.PASSWORD
            h.any { it.contains("otp") || it == "one-time-code" } -> FieldType.OTP
            h.any { it.startsWith("creditcard") || it.startsWith("cc-") } -> FieldType.IGNORE
            h.any { it == "username" || it == "emailaddress" || it == "email" || it == "newusername" || it == "phonenumber" } -> FieldType.USERNAME
            else -> null
        }
    }

    private fun fromHtml(f: FieldInfo): FieldType? {
        if (f.htmlTag != null && f.htmlTag.lowercase() != "input") return null
        val type = f.htmlAttributes["type"]?.lowercase()
        val autocomplete = f.htmlAttributes["autocomplete"]?.lowercase().orEmpty()
        if (type in setOf("hidden", "submit", "button", "checkbox", "radio", "search", "file", "image", "reset", "range", "color", "date")) {
            return FieldType.IGNORE
        }
        return when {
            "one-time-code" in autocomplete -> FieldType.OTP
            autocomplete.split(' ').any { it.startsWith("cc-") } -> FieldType.IGNORE
            "new-password" in autocomplete -> FieldType.NEW_PASSWORD
            "current-password" in autocomplete -> FieldType.PASSWORD
            type == "password" -> null // let keywords decide password vs confirm/new
            "username" in autocomplete || autocomplete.split(' ').contains("email") -> FieldType.USERNAME
            type == "email" -> FieldType.USERNAME
            else -> null
        }
    }

    /** Lowercased descriptive text: resource id, hint, html name/id/placeholder/label. */
    internal fun keywords(f: FieldInfo): String = buildString {
        f.idEntry?.let { append(it).append(' ') }
        f.hint?.let { append(it).append(' ') }
        listOf("name", "id", "placeholder", "aria-label", "label", "title").forEach { k ->
            f.htmlAttributes[k]?.let { append(it).append(' ') }
        }
    }.lowercase()
}
