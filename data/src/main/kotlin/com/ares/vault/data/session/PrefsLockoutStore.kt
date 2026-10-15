package com.ares.vault.data.session

import android.content.Context
import androidx.core.content.edit
import com.ares.vault.core.policy.LockoutState
import com.ares.vault.core.policy.LockoutStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failed-attempt counter. Not secret, so plain prefs are fine; excluded from backup by the
 * extraction rules. Clearing app data also deletes the vault, so resetting it gains nothing.
 */
@Singleton
class PrefsLockoutStore @Inject constructor(@ApplicationContext context: Context) : LockoutStore {
    private val prefs = context.getSharedPreferences("lockout", Context.MODE_PRIVATE)

    override fun load() = LockoutState(
        failures = prefs.getInt(FAILURES, 0),
        lastFailureWall = prefs.getLong(WALL, 0),
        lastFailureElapsed = prefs.getLong(ELAPSED, 0),
        bootCount = prefs.getInt(BOOT, -1),
    )

    override fun save(state: LockoutState) = prefs.edit(commit = true) {
        putInt(FAILURES, state.failures)
        putLong(WALL, state.lastFailureWall)
        putLong(ELAPSED, state.lastFailureElapsed)
        putInt(BOOT, state.bootCount)
    }

    private companion object {
        const val FAILURES = "failures"
        const val WALL = "last_wall"
        const val ELAPSED = "last_elapsed"
        const val BOOT = "boot_count"
    }
}
