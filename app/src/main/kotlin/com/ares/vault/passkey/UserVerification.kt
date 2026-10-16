package com.ares.vault.passkey

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers when the user last proved presence (unlock or fingerprint/PIN confirm) inside a
 * passkey flow. Credential Manager's "Unlock AresVault" step and the following sign-in are two
 * separate activity launches seconds apart; asking for the fingerprint twice would be noise.
 */
@Singleton
class UserVerification @Inject constructor() {
    @Volatile private var verifiedAt = Long.MIN_VALUE / 2

    fun markVerified() {
        verifiedAt = SystemClock.elapsedRealtime()
    }

    fun recentlyVerified(windowMs: Long = WINDOW_MS): Boolean = SystemClock.elapsedRealtime() - verifiedAt in 0..windowMs

    /** Consumed by one operation so a single verification can't authorise a series of them. */
    fun consume(): Boolean = recentlyVerified().also { if (it) verifiedAt = Long.MIN_VALUE / 2 }

    private companion object {
        const val WINDOW_MS = 30_000L
    }
}
