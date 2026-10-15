package com.ares.vault.core.generator

import java.security.SecureRandom

/** Deterministic CSPRNG for reproducible statistical tests. */
fun seededRandom(seed: Long = 42L): UniformRandom =
    UniformRandom(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) })

/** Pearson chi-square statistic against a uniform expectation. */
fun chiSquare(counts: Collection<Long>): Double {
    val expected = counts.sum().toDouble() / counts.size
    return counts.sumOf { (it - expected) * (it - expected) / expected }
}

/** Chi-square critical values at p = 0.001, keyed by degrees of freedom. */
val CHI2_P001 = mapOf(2 to 13.82, 5 to 20.52, 6 to 22.46, 9 to 27.88, 15 to 37.70, 25 to 52.62)
