package com.ares.vault.data.session

import com.ares.vault.core.policy.VaultClock
import com.ares.vault.data.AresLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec §4.3 auto-lock. Locks after [timeoutSeconds] in the background and (optionally)
 * when the screen turns off.
 *
 * XOS freezes background processes, so a delay() timer may fire late. Every entry point
 * that touches the vault (UI resume, autofill requests) therefore also calls
 * [lockIfExpired], which compares monotonic time instead of trusting the timer.
 */
@Singleton
class AutoLockController @Inject constructor(
    private val session: VaultSession,
    private val settings: SettingsRepository,
    private val clock: VaultClock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backgroundSince: Long? = null
    private var timer: Job? = null

    @Volatile var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
        private set
    @Volatile var lockOnScreenOff: Boolean = true
        private set

    init {
        // Cache the (encrypted) settings while unlocked; the values are needed while locked
        // only to decide *when* to lock, never to unlock.
        scope.launch {
            settings.observe().collectLatest { s ->
                if (s != null) {
                    timeoutSeconds = s.autoLockSeconds
                    lockOnScreenOff = s.lockOnScreenOff
                }
            }
        }
    }

    fun onAppBackground() {
        if (!session.isUnlocked) return
        backgroundSince = clock.elapsedMillis()
        timer?.cancel()
        if (timeoutSeconds == NEVER) return
        timer = scope.launch {
            delay(timeoutSeconds * 1000L)
            lock("background timeout")
        }
    }

    fun onAppForeground() {
        lockIfExpired()
        backgroundSince = null
        timer?.cancel()
    }

    fun onScreenOff() {
        if (lockOnScreenOff && session.isUnlocked) lock("screen off")
    }

    /** Locks if the background timeout has passed. Returns true if the vault is (now) locked. */
    fun lockIfExpired(): Boolean {
        val since = backgroundSince
        if (session.isUnlocked && since != null && timeoutSeconds != NEVER &&
            clock.elapsedMillis() - since >= timeoutSeconds * 1000L
        ) {
            lock("background timeout (late check)")
        }
        return !session.isUnlocked
    }

    /** Treat activity inside autofill UI as foreground use so it isn't locked mid-flow. */
    fun touch() {
        if (backgroundSince != null) backgroundSince = clock.elapsedMillis()
    }

    private fun lock(reason: String) {
        if (!session.isUnlocked) return
        AresLog.i("AutoLock", "Locking: $reason")
        session.lock()
        timer?.cancel()
        backgroundSince = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val NEVER = -1
        /** Options shown in Settings (seconds). 0 = immediately on leaving the app. */
        val TIMEOUT_OPTIONS = listOf(0, 30, 60, 120, 300, 900, 3600)
    }
}
