package com.ares.vault.core.crypto

import kotlinx.serialization.Serializable

/** Argon2id cost parameters. Stored in the vault header next to the salt. */
@Serializable
data class Argon2Params(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputLength: Int = 32,
) {
    init {
        require(memoryKiB >= 8 * parallelism) { "memory too small" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(outputLength in 16..64) { "output length out of range" }
    }

    /** Canonical encoding bound into AES-GCM AAD so params cannot be swapped silently. */
    fun canonical(): String = "argon2id:m=$memoryKiB,t=$iterations,p=$parallelism,l=$outputLength"

    companion object {
        /** Spec §4.1 baseline: 64 MiB, 3 iterations, 1 lane, 256-bit output. */
        val DEFAULT = Argon2Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)
    }
}
