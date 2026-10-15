package com.ares.vault.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KdfCalibratorTest {
    /** Fake KDF whose cost is [msPerIteration] of simulated time per iteration. */
    private class FakeKdf(val msPerIteration: Long) : Kdf {
        var now = 0L
        override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
            now += msPerIteration * params.iterations * 1_000_000
            return ByteArray(params.outputLength)
        }
    }

    private fun calibrate(msPerIteration: Long): KdfCalibrator.Result {
        val kdf = FakeKdf(msPerIteration)
        return KdfCalibrator(kdf) { kdf.now }.calibrate()
    }

    @Test fun slowDeviceKeepsBaseline() {
        val r = calibrate(msPerIteration = 300) // 900 ms at t=3
        assertThat(r.params).isEqualTo(Argon2Params.DEFAULT)
        assertThat(r.measuredMillis).isEqualTo(900)
    }

    @Test fun fastDeviceRaisesIterationsIntoTargetWindow() {
        val r = calibrate(msPerIteration = 50) // 150 ms at t=3
        assertThat(r.params.iterations).isEqualTo(15)
        assertThat(r.params.memoryKiB).isEqualTo(Argon2Params.DEFAULT.memoryKiB)
        assertThat(r.measuredMillis).isIn(500L..1000L)
    }

    @Test fun veryFastDeviceIsCappedAtMaxIterations() {
        val r = calibrate(msPerIteration = 1)
        assertThat(r.params.iterations).isEqualTo(16)
    }
}
