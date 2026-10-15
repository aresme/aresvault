package com.ares.vault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.data.session.AutoLockController
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AutoLockControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = VaultSession()
    private val clock = FakeClock()
    private lateinit var autoLock: AutoLockController

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        autoLock = AutoLockController(session, SettingsRepository(session), clock)
        session.openInMemory()
    }

    @After fun tearDown() {
        session.lock()
        Dispatchers.resetMain()
    }

    @Test fun locksAfterBackgroundTimeout() = runTest(dispatcher) {
        runCurrent()
        autoLock.onAppBackground()
        advanceTimeBy(119_000)
        assertThat(session.isUnlocked).isTrue()
        advanceTimeBy(2_000)
        assertThat(session.isUnlocked).isFalse()
    }

    @Test fun lateCheckLocksWhenTimerWasFrozen() = runTest(dispatcher) {
        runCurrent()
        autoLock.onAppBackground()
        // Simulate XOS freezing the process: the timer never runs, but monotonic time passes.
        clock.elapsed += 121_000
        assertThat(autoLock.lockIfExpired()).isTrue()
        assertThat(session.isUnlocked).isFalse()
    }

    @Test fun returningInTimeKeepsVaultOpen() = runTest(dispatcher) {
        runCurrent()
        autoLock.onAppBackground()
        clock.elapsed += 60_000
        advanceTimeBy(60_000)
        autoLock.onAppForeground()
        advanceTimeBy(600_000)
        assertThat(session.isUnlocked).isTrue()
    }

    @Test fun screenOffLocksImmediately() = runTest(dispatcher) {
        runCurrent()
        autoLock.onScreenOff()
        assertThat(session.isUnlocked).isFalse()
    }
}
