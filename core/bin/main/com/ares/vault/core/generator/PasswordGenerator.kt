package com.ares.vault.core.generator

import kotlin.math.log2

/**
 * Generates passwords/passphrases with a CSPRNG. Output is a [CharArray] so callers can
 * wipe it; nothing here creates a String of the secret.
 */
class PasswordGenerator(
    private val random: UniformRandom = UniformRandom(),
    private val wordlist: () -> List<String> = { EffWordlist.words },
) {
    fun generate(config: GeneratorConfig): CharArray = when (config) {
        is RandomConfig -> random(config)
        is PassphraseConfig -> passphrase(config)
    }

    /**
     * 1. Draw each class's minimum from that class (guarantees e.g. ≥1 digit).
     * 2. Fill the rest uniformly from the union of all enabled classes.
     * 3. Fisher–Yates shuffle so guaranteed characters are not at predictable positions.
     */
    fun random(config: RandomConfig): CharArray {
        config.validationError()?.let { throw IllegalArgumentException(it) }
        val pools = config.pools()
        val union = pools.joinToString("") { it.chars }
        val out = CharArray(config.length)
        var i = 0
        for (pool in pools) {
            repeat(pool.min) { out[i++] = pool.chars[random.nextInt(pool.chars.length)] }
        }
        while (i < out.size) out[i++] = union[random.nextInt(union.length)]
        random.shuffle(out)
        return out
    }

    /**
     * Words joined by the separator. With [PassphraseConfig.includeNumber], one random digit
     * is appended to one randomly chosen word.
     */
    fun passphrase(config: PassphraseConfig): CharArray {
        config.validationError()?.let { throw IllegalArgumentException(it) }
        val list = wordlist()
        val picked = List(config.words) { list[random.nextInt(list.size)] }
        val numberAt = if (config.includeNumber) random.nextInt(config.words) else -1
        val digit = if (config.includeNumber) CharSets.DIGITS[random.nextInt(10)] else ' '

        // Written straight into a CharArray (no StringBuilder/String copies to leak).
        val length = picked.sumOf { it.length } + config.separator.length * (config.words - 1) +
            (if (config.includeNumber) 1 else 0)
        val out = CharArray(length)
        var i = 0
        picked.forEachIndexed { index, word ->
            if (index > 0) config.separator.forEach { out[i++] = it }
            word.forEachIndexed { k, c -> out[i++] = if (k == 0 && config.capitalize) c.uppercaseChar() else c }
            if (index == numberAt) out[i++] = digit
        }
        return out
    }

    companion object {
        /**
         * Entropy in bits of the generator's output distribution (not of a given string).
         * Random: `length × log2(|union|)`, slightly overstating by the class-guarantee
         * constraint (< 1 bit for presets). Passphrase: `words × log2(7776)` plus
         * `log2(10 × words)` for the digit and its position; capitalisation adds nothing.
         */
        fun entropyBits(config: GeneratorConfig): Double = when (config) {
            is RandomConfig -> {
                val union = config.pools().sumOf { it.chars.length }
                if (union == 0) 0.0 else config.length * log2(union.toDouble())
            }
            is PassphraseConfig -> {
                val words = config.words * log2(EffWordlist.SIZE.toDouble())
                words + if (config.includeNumber) log2(10.0 * config.words) else 0.0
            }
        }
    }
}

/**
 * Coarse label for an entropy figure, used by the strength bar. Thresholds line up with
 * the presets: Medium ≈ 71 bits → Good, Strong ≈ 104 → Strong, Very strong ≈ 155,
 * Maximum ≈ 207 → Excellent.
 */
enum class StrengthLevel(val label: String, val minBits: Double) {
    WEAK("Weak", 0.0),
    FAIR("Fair", 40.0),
    GOOD("Good", 60.0),
    STRONG("Strong", 80.0),
    VERY_STRONG("Very strong", 128.0),
    EXCELLENT("Excellent", 180.0),
    ;

    companion object {
        fun forBits(bits: Double): StrengthLevel = entries.last { bits >= it.minBits }

        /** 0..1 fill for a strength bar; saturates at 180 bits. */
        fun fraction(bits: Double): Float = (bits / 180.0).coerceIn(0.0, 1.0).toFloat()
    }
}
