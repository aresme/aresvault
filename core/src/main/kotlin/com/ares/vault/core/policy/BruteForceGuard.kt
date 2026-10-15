package com.ares.vault.core.policy

import kotlin.math.max
import kotlin.math.min

/** Time sources. Android implementation uses System/SystemClock + Settings.Global.BOOT_COUNT. */
interface VaultClock {
    fun wallMillis(): Long
    /** Monotonic since boot; cannot be changed by the user. */
    fun elapsedMillis(): Long
    fun bootCount(): Int
}

data class LockoutState(
    val failures: Int = 0,
    val lastFailureWall: Long = 0,
    val lastFailureElapsed: Long = 0,
    val bootCount: Int = -1,
)

interface LockoutStore {
    fun load(): LockoutState
    fun save(state: LockoutState)
}

/**
 * Spec §4.3: after 5 wrong master passwords, enforce increasing delays.
 *
 * This only slows down guessing through the app UI; an attacker with the header file
 * attacks Argon2id offline instead. The remaining time is the max of a monotonic
 * (same-boot) and a wall-clock estimate so that neither changing the clock nor
 * rebooting shortens it; a wall clock moved backwards restarts the full delay.
 */
class BruteForceGuard(private val store: LockoutStore, private val clock: VaultClock) {

    fun remainingMillis(): Long {
        val s = store.load()
        val delay = delayMillis(s.failures)
        if (delay == 0L) return 0
        val byElapsed = if (s.bootCount == clock.bootCount()) {
            s.lastFailureElapsed + delay - clock.elapsedMillis()
        } else {
            Long.MIN_VALUE
        }
        val now = clock.wallMillis()
        val byWall = if (now < s.lastFailureWall) delay else s.lastFailureWall + delay - now
        return max(0L, max(byElapsed, byWall))
    }

    /** Returns the delay now in force (0 if still within the free attempts). */
    fun recordFailure(): Long {
        val s = store.load()
        store.save(
            LockoutState(
                failures = s.failures + 1,
                lastFailureWall = clock.wallMillis(),
                lastFailureElapsed = clock.elapsedMillis(),
                bootCount = clock.bootCount(),
            ),
        )
        return delayMillis(s.failures + 1)
    }

    fun failures(): Int = store.load().failures

    fun reset() = store.save(LockoutState())

    companion object {
        const val FREE_ATTEMPTS = 5
        private val STEPS_SECONDS = longArrayOf(30, 60, 120, 300, 900, 1800, 3600)

        fun delayMillis(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0
            return STEPS_SECONDS[min(failures - FREE_ATTEMPTS, STEPS_SECONDS.lastIndex)] * 1000
        }
    }
}
