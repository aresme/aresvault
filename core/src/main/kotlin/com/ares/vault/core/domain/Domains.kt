package com.ares.vault.core.domain

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

object Domains {
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

    /**
     * Turns user input ("https://Login.Example.co.ke/path", "example.com", "bücher.de")
     * into a lowercase ASCII host ("login.example.co.ke", "xn--bcher-kva.de").
     * Returns null when the input is not a plausible web host.
     */
    fun normalizeHost(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val rawHost = try {
            URI(withScheme).host ?: hostFallback(withScheme)
        } catch (_: URISyntaxException) {
            hostFallback(withScheme)
        } ?: return null
        val ascii = try {
            IDN.toASCII(rawHost.trimEnd('.'), IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!HOST.matches(ascii)) return null
        if ('.' !in ascii && ascii != "localhost") return null
        return ascii
    }

    /** URI.getHost() returns null for non-ASCII hosts; extract it manually. */
    private fun hostFallback(url: String): String? {
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.ifEmpty { null }
    }
}
