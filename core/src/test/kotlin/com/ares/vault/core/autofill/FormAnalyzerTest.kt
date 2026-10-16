package com.ares.vault.core.autofill

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormAnalyzerTest {
    private val TEXT = 0x1
    private val TEXT_PASSWORD = 0x81
    private val TEXT_WEB_PASSWORD = 0xe1
    private val TEXT_EMAIL = 0x21
    private val NUMBER_PASSWORD = 0x12
    private val NUMBER = 0x2

    private fun input(vararg attrs: Pair<String, String>) =
        FieldInfo(htmlTag = "input", htmlAttributes = attrs.toMap(), isTextInput = attrs.toMap()["type"] in setOf(null, "text", "email", "tel", "password"))

    private fun native(inputType: Int, id: String? = null, hint: String? = null, hints: List<String> = emptyList()) =
        FieldInfo(inputType = inputType, idEntry = id, hint = hint, autofillHints = hints, isTextInput = true, className = "android.widget.EditText")

    private fun analyze(vararg f: FieldInfo, page: String = "") =
        FormAnalyzer.analyze(f.mapIndexed { i, info -> i to info }, page)

    @Test fun nativeLoginByInputType() {
        val a = analyze(native(TEXT, id = "et_login"), native(TEXT_PASSWORD, id = "et_secret"))
        assertThat(a.type).isEqualTo(FormType.LOGIN)
        assertThat(a.username).isEqualTo(0)
        assertThat(a.password).isEqualTo(1)
    }

    @Test fun autofillHintsWin() {
        val a = analyze(native(TEXT, hints = listOf("emailAddress")), native(TEXT, hints = listOf("password")))
        assertThat(a.type).isEqualTo(FormType.LOGIN)
        assertThat(a.password).isEqualTo(1)
    }

    @Test fun webLogin() {
        val a = analyze(input("type" to "email", "name" to "email"), input("type" to "password", "autocomplete" to "current-password"))
        assertThat(a.type).isEqualTo(FormType.LOGIN)
        assertThat(a.username).isEqualTo(0)
    }

    @Test fun webSignUpByAutocomplete() {
        val a = analyze(input("type" to "email"), input("type" to "password", "autocomplete" to "new-password"))
        assertThat(a.type).isEqualTo(FormType.SIGN_UP)
        assertThat(a.newPasswords).containsExactly(1)
    }

    @Test fun twoPasswordsMeansSignUpWithConfirm() {
        val a = analyze(
            native(TEXT, id = "username"),
            native(TEXT_PASSWORD, id = "password"),
            native(TEXT_PASSWORD, id = "confirm_password"),
        )
        assertThat(a.type).isEqualTo(FormType.SIGN_UP)
        assertThat(a.username).isEqualTo(0)
        assertThat(a.newPasswords).containsExactly(1)
        assertThat(a.confirmPasswords).containsExactly(2)
    }

    @Test fun signUpKeywordsOnPage() {
        val a = analyze(native(TEXT_EMAIL), native(TEXT_PASSWORD), page = "Create account Already have one?")
        assertThat(a.type).isEqualTo(FormType.SIGN_UP)
    }

    @Test fun swahiliSignUpAndFields() {
        val a = analyze(native(TEXT, hint = "Jina la mtumiaji"), native(TEXT, hint = "Nenosiri"), page = "Jisajili")
        assertThat(a.type).isEqualTo(FormType.SIGN_UP)
        assertThat(a.username).isEqualTo(0)
        assertThat(a.newPasswords).containsExactly(1)
    }

    @Test fun usernameOnlyStep() {
        val a = analyze(input("type" to "email", "autocomplete" to "username"))
        assertThat(a.type).isEqualTo(FormType.LOGIN)
        assertThat(a.username).isEqualTo(0)
        assertThat(a.password).isNull()
    }

    @Test fun untypedTextBeforePasswordIsUsername() {
        val a = analyze(native(TEXT, id = "field_a"), native(TEXT_WEB_PASSWORD))
        assertThat(a.username).isEqualTo(0)
    }

    @Test fun otpAndSearchAreSkipped() {
        // A lone one-time-code field is its own form type (filled with a 2FA code), never a login.
        assertThat(analyze(native(NUMBER, id = "otp_code")).type).isEqualTo(FormType.OTP)
        assertThat(analyze(native(NUMBER, id = "otp_code")).otp).isEqualTo(0)
        assertThat(analyze(input("type" to "text", "autocomplete" to "one-time-code")).type).isEqualTo(FormType.OTP)
        assertThat(analyze(input("type" to "search", "name" to "q")).type).isEqualTo(FormType.NONE)
        // An OTP field next to a password is not taken as the username.
        val a = analyze(native(NUMBER, id = "sms_code"), native(TEXT_PASSWORD))
        assertThat(a.username).isNull()
    }

    @Test fun pinIsPasswordButShippingIsNot() {
        assertThat(FieldClassifier.classify(native(NUMBER_PASSWORD, id = "pin"))).isEqualTo(FieldType.PASSWORD)
        assertThat(FieldClassifier.classify(native(TEXT, id = "shipping_address"))).isEqualTo(FieldType.UNKNOWN)
    }

    @Test fun invisibleOrUnfocusableFieldsAreIgnored() {
        val hidden = native(TEXT_PASSWORD).copy(visible = false)
        val disabled = native(TEXT_PASSWORD).copy(focusable = false)
        assertThat(FieldClassifier.classify(hidden)).isEqualTo(FieldType.IGNORE)
        assertThat(FieldClassifier.classify(disabled)).isEqualTo(FieldType.IGNORE)
        assertThat(analyze(native(TEXT, id = "user"), hidden).password).isNull()
    }

    @Test fun creditCardsAndHiddenInputsIgnored() {
        assertThat(FieldClassifier.classify(input("autocomplete" to "cc-number"))).isEqualTo(FieldType.IGNORE)
        assertThat(FieldClassifier.classify(input("type" to "hidden", "name" to "password"))).isEqualTo(FieldType.IGNORE)
        assertThat(FieldClassifier.classify(native(TEXT, hints = listOf("creditCardNumber")))).isEqualTo(FieldType.IGNORE)
    }
}
