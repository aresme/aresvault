package com.ares.vault.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret UI flags that must be readable while the vault is locked. */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(ONBOARDING_DONE, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun setOnboardingDone(done: Boolean) {
        prefs.edit { putBoolean(ONBOARDING_DONE, done) }
        _onboardingDone.value = done
    }

    /** The user wants fingerprint unlock; used to offer re-enrolment after invalidation. */
    var biometricWanted: Boolean
        get() = prefs.getBoolean(BIOMETRIC_WANTED, false)
        set(value) = prefs.edit { putBoolean(BIOMETRIC_WANTED, value) }

    /** Dedicated address for emailed encrypted backups (not secret; app-private prefs). */
    var backupEmail: String
        get() = prefs.getString(BACKUP_EMAIL, "").orEmpty()
        set(value) = prefs.edit { putString(BACKUP_EMAIL, value.trim()) }

    var emailReminderEnabled: Boolean
        get() = prefs.getBoolean(EMAIL_REMINDER, true)
        set(value) = prefs.edit { putBoolean(EMAIL_REMINDER, value) }

    /** When an encrypted backup was last handed to the email app (wall clock, ms; 0 = never). */
    var lastEmailBackupAt: Long
        get() = prefs.getLong(LAST_EMAIL_BACKUP, 0)
        set(value) = prefs.edit { putLong(LAST_EMAIL_BACKUP, value) }

    fun clear() {
        prefs.edit { clear() }
        _onboardingDone.value = false
    }

    private companion object {
        const val ONBOARDING_DONE = "onboarding_done"
        const val BIOMETRIC_WANTED = "biometric_wanted"
        const val BACKUP_EMAIL = "backup_email"
        const val EMAIL_REMINDER = "email_reminder"
        const val LAST_EMAIL_BACKUP = "last_email_backup"
    }
}
