package com.ares.vault.core.autofill

import java.net.IDN

/**
 * Registrable-domain (eTLD+1) lookup using the bundled Mozilla Public Suffix List
 * (MPL 2.0, publicsuffix.org). Bundled, never fetched — the app has no network access.
 *
 * Both the ICANN and PRIVATE sections are used, so e.g. `alice.github.io` and
 * `bob.github.io` are *different* sites and never share credentials.
 */
class PublicSuffixList private constructor(
    private val exact: Set<String>,
    private val wildcards: Set<String>,
    private val exceptions: Set<String>,
) {
    /**
     * `login.example.co.ke` → `example.co.ke`; `example.com` → `example.com`;
     * `co.ke` (itself a public suffix) → null. Input should be a lowercase host.
     */
    fun registrableDomain(host: String): String? {
        val ascii = toAscii(host.trim().trimEnd('.').lowercase()) ?: return null
        if (ascii.isEmpty() || ascii.startsWith('.') || ".." in ascii) return null
        val labels = ascii.split('.')
        val suffixLabels = publicSuffixLength(labels)
        if (labels.size <= suffixLabels) return null
        return labels.takeLast(suffixLabels + 1).joinToString(".")
    }

    /** Number of labels in the public suffix of [labels] (PSL algorithm, implicit "*" rule). */
    private fun publicSuffixLength(labels: List<String>): Int {
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in exceptions) return labels.size - i - 1
            if (candidate in exact) return labels.size - i
            if (i + 1 < labels.size && labels.subList(i + 1, labels.size).joinToString(".") in wildcards) {
                return labels.size - i
            }
        }
        return 1
    }

    companion object {
        // Absolute path: R8 moves classes to other packages, which breaks relative lookups.
        private const val RESOURCE = "/com/ares/vault/core/autofill/public_suffix_list.dat"

        val default: PublicSuffixList by lazy {
            val stream = checkNotNull(PublicSuffixList::class.java.getResourceAsStream(RESOURCE)) { "PSL missing" }
            stream.bufferedReader().useLines { parse(it) }
        }

        fun parse(lines: Sequence<String>): PublicSuffixList {
            val exact = HashSet<String>(16_384)
            val wildcards = HashSet<String>()
            val exceptions = HashSet<String>()
            lines.forEach { raw ->
                val line = raw.trim().substringBefore(' ')
                if (line.isEmpty() || line.startsWith("//")) return@forEach
                when {
                    line.startsWith("!") -> toAscii(line.drop(1))?.let(exceptions::add)
                    line.startsWith("*.") -> toAscii(line.drop(2))?.let(wildcards::add)
                    else -> toAscii(line)?.let(exact::add)
                }
            }
            return PublicSuffixList(exact, wildcards, exceptions)
        }

        private fun toAscii(s: String): String? = try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
