package com.ares.vault.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.backup.EmailBackup
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmailBackupTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun reminderIsWeeklyAndNeedsAnAddress() {
        val now = 100 * day
        assertThat(EmailBackup.isReminderDue(true, "me@example.com", lastSentAt = 0, now = now)).isTrue()
        assertThat(EmailBackup.isReminderDue(true, "me@example.com", lastSentAt = now - 6 * day, now = now)).isFalse()
        assertThat(EmailBackup.isReminderDue(true, "me@example.com", lastSentAt = now - 7 * day, now = now)).isTrue()
        assertThat(EmailBackup.isReminderDue(false, "me@example.com", lastSentAt = 0, now = now)).isFalse()
        assertThat(EmailBackup.isReminderDue(true, "", lastSentAt = 0, now = now)).isFalse()
    }

    @Test fun validatesAddresses() {
        assertThat(EmailBackup.isValidAddress("kip.backup@gmail.com")).isTrue()
        assertThat(EmailBackup.isValidAddress(" kip@proton.me ")).isTrue()
        assertThat(EmailBackup.isValidAddress("not an email")).isFalse()
        assertThat(EmailBackup.isValidAddress("kip@")).isFalse()
    }
}
