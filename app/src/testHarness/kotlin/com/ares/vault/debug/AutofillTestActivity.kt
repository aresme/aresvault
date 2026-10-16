package com.ares.vault.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only forms for exercising AresVault autofill:
 *
 *   adb shell am start -n com.ares.vault/com.ares.vault.debug.AutofillTestActivity --es mode login|signup|web
 *
 * "web" loads a page claiming to be https://example.com inside *this* (non-browser) app; the
 * service must ignore that webDomain and treat the request as the app itself (spec §4.4).
 */
class AutofillTestActivity : Activity() {
    lateinit var username: EditText
        private set
    lateinit var password: EditText
        private set
    var confirm: EditText? = null
        private set

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "login"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
        }
        fun field(desc: String, hint: String, type: Int, autofillHint: String?): EditText = EditText(this).apply {
            contentDescription = desc
            this.hint = hint
            inputType = type
            autofillHint?.let { setAutofillHints(it) }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val text = InputType.TYPE_CLASS_TEXT
        when (mode) {
            "signup" -> {
                root.addView(TextView(this).apply { this.text = "Create account"; textSize = 24f })
                username = field("username", "Email", text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_EMAIL_ADDRESS)
                password = field("password", "New password", text or InputType.TYPE_TEXT_VARIATION_PASSWORD, "newPassword")
                confirm = field("confirm", "Confirm password", text or InputType.TYPE_TEXT_VARIATION_PASSWORD, null)
                root.addView(Button(this).apply { this.text = "Sign up"; setOnClickListener { finish() } })
            }
            "passkey" -> {
                root.addView(TextView(this).apply { this.text = "Passkey test (${PasskeyHarness.RP})"; textSize = 24f })
                username = EditText(this)
                password = EditText(this)
                val status = TextView(this).apply { contentDescription = "passkey_status"; this.text = "idle" }
                val harness = PasskeyHarness(this, status)
                root.addView(Button(this).apply { this.text = "Create passkey"; setOnClickListener { harness.create() } })
                root.addView(Button(this).apply { this.text = "Sign in with passkey"; setOnClickListener { harness.signIn() } })
                root.addView(status)
            }
            "reminder" -> {
                // Posts the weekly backup reminder now (instead of waiting a week).
                username = EditText(this)
                password = EditText(this)
                com.ares.vault.backup.BackupReminderWorker.notify(this, com.ares.vault.prefs.AppPrefs(this).backupEmail)
                finish()
            }
            "otp" -> {
                root.addView(TextView(this).apply { this.text = "Enter the 6-digit code"; textSize = 24f })
                username = EditText(this)
                password = field("otp", "Verification code", InputType.TYPE_CLASS_NUMBER, "smsOTPCode")
                root.addView(Button(this).apply { this.text = "Verify"; setOnClickListener { finish() } })
            }
            "web" -> {
                username = EditText(this)
                password = EditText(this)
                root.addView(TextView(this).apply { this.text = "WebView claiming example.com (should be treated as this app)" })
                root.addView(
                    WebView(this).apply {
                        settings.javaScriptEnabled = false
                        loadDataWithBaseURL(
                            "https://example.com/login",
                            """<html><body><h2>Log in to example.com</h2>
                               <input type="email" name="email" autocomplete="username" placeholder="Email"><br>
                               <input type="password" name="password" autocomplete="current-password" placeholder="Password">
                               </body></html>""",
                            "text/html", "utf-8", null,
                        )
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 900),
                )
            }
            else -> {
                root.addView(TextView(this).apply { this.text = "Log in"; textSize = 24f })
                username = field("username", "Username or email", text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_USERNAME)
                password = field("password", "Password", text or InputType.TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD)
                root.addView(Button(this).apply { this.text = "Sign in"; setOnClickListener { finish() } })
            }
        }
        setContentView(root)
    }
}
