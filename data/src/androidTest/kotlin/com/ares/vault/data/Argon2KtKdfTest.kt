package com.ares.vault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.BouncyCastleArgon2Kdf
import com.ares.vault.core.crypto.KdfCalibrator
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.data.crypto.Argon2KtKdf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Cross-checks the native Argon2kt output against Bouncy Castle on the real device. */
@RunWith(AndroidJUnit4::class)
class Argon2KtKdfTest {
    private val native = Argon2KtKdf()
    private val reference = BouncyCastleArgon2Kdf()

    @Test fun matchesBouncyCastle() {
        val cases = listOf(
            Argon2Params(memoryKiB = 1024, iterations = 1, parallelism = 1),
            Argon2Params(memoryKiB = 4096, iterations = 3, parallelism = 2, outputLength = 64),
            Argon2Params.DEFAULT,
        )
        for (params in cases) {
            val password = "pässwörd 🔑".toByteArray()
            val salt = SecureBytes.random(16)
            assertThat(native.derive(password, salt, params))
                .isEqualTo(reference.derive(password, salt, params))
        }
    }

    @Test fun inputsAreNotModified() {
        val password = byteArrayOf(1, 2, 3, 4)
        val salt = ByteArray(16) { 7 }
        native.derive(password, salt, Argon2Params(1024, 1, 1))
        assertThat(password).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(salt).isEqualTo(ByteArray(16) { 7 })
    }

    @Test fun calibrationLandsInWindow() {
        val result = KdfCalibrator(native).calibrate()
        android.util.Log.i("AresVault", "[Test] calibrated ${result.params.canonical()} in ${result.measuredMillis} ms")
        assertThat(result.params.memoryKiB).isEqualTo(64 * 1024)
        assertThat(result.params.iterations).isAtLeast(3)
    }
}
