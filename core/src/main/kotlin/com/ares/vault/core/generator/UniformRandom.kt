package com.ares.vault.core.generator

import java.security.SecureRandom

/**
 * Unbiased integers in `[0, bound)` from a CSPRNG via explicit rejection sampling.
 *
 * Taking `random32 % bound` directly would favour small values whenever 2^32 is not a
 * multiple of `bound`. We discard draws from the incomplete top "bucket" (`r >= limit`)
 * so every residue has exactly the same number of preimages.
 */
class UniformRandom(private val next32: () -> Int) {
    constructor(random: SecureRandom = SecureRandom()) : this(random::nextInt)

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val limit = RANGE - RANGE % bound
        while (true) {
            val r = next32().toLong() and 0xFFFF_FFFFL
            if (r < limit) return (r % bound).toInt()
        }
    }

    /** In-place Fisher–Yates shuffle using [nextInt]. */
    fun shuffle(chars: CharArray) {
        for (i in chars.lastIndex downTo 1) {
            val j = nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
    }

    private companion object {
        const val RANGE = 1L shl 32
    }
}
