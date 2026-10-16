package com.ares.vault.core.generator

/**
 * EFF Large Wordlist (7776 words, 12.925 bits/word), bundled — never fetched.
 * © Electronic Frontier Foundation, CC BY 3.0.
 */
object EffWordlist {
    const val SIZE = 7776
    // Absolute path: R8 moves classes to other packages, which breaks relative lookups.
    private const val RESOURCE = "/com/ares/vault/core/generator/eff_large_wordlist.txt"

    val words: List<String> by lazy {
        val stream = checkNotNull(EffWordlist::class.java.getResourceAsStream(RESOURCE)) {
            "Wordlist resource missing"
        }
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { it.substringAfter('\t').trim() }.toList()
        }.also { check(it.size == SIZE) { "Wordlist corrupt: ${it.size} words" } }
    }
}
