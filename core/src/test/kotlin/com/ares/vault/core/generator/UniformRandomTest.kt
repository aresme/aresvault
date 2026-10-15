package com.ares.vault.core.generator

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class UniformRandomTest {
    private fun scripted(vararg values: Int): Pair<UniformRandom, () -> Int> {
        var i = 0
        return UniformRandom { values[i++] } to { i }
    }

    @Test fun drawsInTheIncompleteTopBucketAreRejected() {
        // For bound 3, 2^32 mod 3 = 1, so exactly one value (0xFFFFFFFF) must be rejected:
        // keeping it would map to residue 0 and bias 0 upwards.
        val (rng, consumed) = scripted(-1, 7)
        assertThat(rng.nextInt(3)).isEqualTo(1)
        assertThat(consumed()).isEqualTo(2)
    }

    @Test fun valuesBelowLimitAreAccepted() {
        // For bound 10, limit = 2^32 - 6; 0xFFFFFFF9 (= limit - 1) is the largest accepted value.
        val (rng, consumed) = scripted(0xFFFFFFF9.toInt())
        assertThat(rng.nextInt(10)).isEqualTo(((0xFFFFFFF9L) % 10).toInt())
        assertThat(consumed()).isEqualTo(1)
        val (rng2, consumed2) = scripted(0xFFFFFFFA.toInt(), 3)
        assertThat(rng2.nextInt(10)).isEqualTo(3)
        assertThat(consumed2()).isEqualTo(2)
    }

    @Test fun boundMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { UniformRandom().nextInt(0) }
        assertThat(UniformRandom().nextInt(1)).isEqualTo(0)
    }

    @Test fun outputIsUniform() {
        val rng = seededRandom()
        for (bound in listOf(3, 7, 10)) {
            val counts = LongArray(bound)
            repeat(100_000) { counts[rng.nextInt(bound)]++ }
            assertThat(chiSquare(counts.toList())).isLessThan(CHI2_P001.getValue(bound - 1))
        }
    }

    @Test fun shuffleProducesAllPermutationsUniformly() {
        val rng = seededRandom()
        val counts = mutableMapOf<String, Long>()
        repeat(60_000) {
            val chars = charArrayOf('a', 'b', 'c')
            rng.shuffle(chars)
            counts.merge(String(chars), 1, Long::plus)
        }
        assertThat(counts).hasSize(6)
        assertThat(chiSquare(counts.values)).isLessThan(CHI2_P001.getValue(5))
    }
}
