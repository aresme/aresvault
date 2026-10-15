package com.ares.vault.core.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BruteForceGuardTest {
    private class FakeClock(var wall: Long = 1_000_000_000L, var elapsed: Long = 50_000L, var boot: Int = 7) : VaultClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        override fun bootCount() = boot
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }

    private class MemoryStore : LockoutStore {
        var state = LockoutState()
        override fun load() = state
        override fun save(state: LockoutState) { this.state = state }
    }

    private val clock = FakeClock()
    private val store = MemoryStore()
    private val guard = BruteForceGuard(store, clock)

    private fun fail(n: Int) = repeat(n) { guard.recordFailure() }

    @Test fun firstFiveFailuresAreFree() {
        fail(4)
        assertThat(guard.remainingMillis()).isEqualTo(0)
        assertThat(guard.recordFailure()).isEqualTo(30_000)
        assertThat(guard.remainingMillis()).isEqualTo(30_000)
    }

    @Test fun delaysIncreaseAndCap() {
        val delays = (1..14).map { guard.recordFailure() }
        assertThat(delays.take(4)).containsExactly(0L, 0L, 0L, 0L)
        assertThat(delays.drop(4)).isInOrder()
        assertThat(delays.last()).isEqualTo(3_600_000)
    }

    @Test fun delayElapses() {
        fail(5)
        clock.advance(29_000)
        assertThat(guard.remainingMillis()).isEqualTo(1_000)
        clock.advance(1_000)
        assertThat(guard.remainingMillis()).isEqualTo(0)
    }

    @Test fun movingWallClockForwardDoesNotHelp() {
        fail(5)
        clock.wall += 10 * 60_000 // user jumps the clock 10 minutes ahead
        assertThat(guard.remainingMillis()).isEqualTo(30_000)
    }

    @Test fun movingWallClockBackRestartsDelay() {
        fail(5)
        clock.advance(20_000)
        clock.wall -= 3_600_000
        assertThat(guard.remainingMillis()).isEqualTo(30_000)
    }

    @Test fun rebootDoesNotReset() {
        fail(6) // 60 s
        clock.advance(10_000)
        clock.boot += 1
        clock.elapsed = 5_000
        assertThat(guard.remainingMillis()).isEqualTo(50_000)
    }

    @Test fun resetClearsEverything() {
        fail(8)
        guard.reset()
        assertThat(guard.remainingMillis()).isEqualTo(0)
        assertThat(guard.failures()).isEqualTo(0)
    }
}
