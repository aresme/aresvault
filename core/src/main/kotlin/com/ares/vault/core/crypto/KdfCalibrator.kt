package com.ares.vault.core.crypto

import kotlin.math.ceil

/**
 * Tunes Argon2id iterations for this device (spec §4.1): if the baseline runs faster
 * than [minMillis], raise iterations so a derivation takes roughly [targetMillis].
 * Memory stays at the baseline because 64 MiB is already a safe ceiling for the
 * autofill process on low-end phones.
 */
class KdfCalibrator(
    private val kdf: Kdf,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Result(val params: Argon2Params, val measuredMillis: Long)

    fun calibrate(
        base: Argon2Params = Argon2Params.DEFAULT,
        minMillis: Long = 500,
        targetMillis: Long = 750,
        maxIterations: Int = 16,
    ): Result {
        val password = SecureBytes.random(16)
        val salt = SecureBytes.random(16)
        try {
            // Warm-up run: the first call also pays for native library loading.
            SecureBytes.wipe(kdf.derive(password, salt, base))
            val elapsed = measure(password, salt, base)
            if (elapsed >= minMillis) return Result(base, elapsed)

            val perIteration = elapsed.coerceAtLeast(1).toDouble() / base.iterations
            val iterations = ceil(targetMillis / perIteration).toInt()
                .coerceIn(base.iterations, maxIterations)
            val tuned = base.copy(iterations = iterations)
            return Result(tuned, measure(password, salt, tuned))
        } finally {
            SecureBytes.wipe(password, salt)
        }
    }

    private fun measure(password: ByteArray, salt: ByteArray, params: Argon2Params): Long {
        val start = nanoTime()
        SecureBytes.wipe(kdf.derive(password, salt, params))
        return (nanoTime() - start) / 1_000_000
    }
}
