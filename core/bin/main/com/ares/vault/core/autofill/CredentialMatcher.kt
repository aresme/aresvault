package com.ares.vault.core.autofill

import com.ares.vault.core.domain.Domains

/** Who is asking for autofill (spec §4.4). */
sealed interface ClientIdentity {
    /** A native app, identified by package AND signing certificate. */
    data class App(val packageName: String, val certSha256: String?) : ClientIdentity

    /** A website reported by an allowlisted, signature-verified browser. */
    data class Web(val host: String, val browserPackage: String) : ClientIdentity
}

/** The identity-relevant fields of a stored credential (works for DB rows and the locked index). */
data class StoredIdentity(
    val appPackage: String?,
    val appCertSha256: String?,
    val webDomain: String?,
    val extraUrls: List<String> = emptyList(),
)

enum class MatchResult { MATCH, NONE, CERT_MISMATCH }

class CredentialMatcher(private val psl: PublicSuffixList = PublicSuffixList.default) {

    /**
     * @param certMatches checks a stored cert against the *installed* app (allows the
     *   developer's verified key-rotation lineage); supplied by the Android layer.
     */
    fun match(
        stored: StoredIdentity,
        client: ClientIdentity,
        certMatches: (packageName: String, storedCert: String) -> Boolean,
    ): MatchResult = when (client) {
        is ClientIdentity.App -> {
            if (stored.appPackage != client.packageName) {
                MatchResult.NONE
            } else if (stored.appCertSha256 != null && certMatches(client.packageName, stored.appCertSha256)) {
                MatchResult.MATCH
            } else {
                // Same package name, different (or unknown) signer: possible impersonation.
                MatchResult.CERT_MISMATCH
            }
        }
        is ClientIdentity.Web -> {
            val target = siteKey(client.host)
            val hosts = listOfNotNull(stored.webDomain) + stored.extraUrls
            if (target != null && hosts.any { h -> Domains.normalizeHost(h)?.let(::siteKey) == target }) {
                MatchResult.MATCH
            } else {
                MatchResult.NONE
            }
        }
    }

    /** Registrable domain, or the host itself for IPs/localhost/single-label hosts. */
    fun siteKey(host: String): String? {
        val normalized = Domains.normalizeHost(host) ?: return null
        return psl.registrableDomain(normalized) ?: normalized.takeIf { isIpOrLocal(it) }
    }

    private fun isIpOrLocal(host: String) = host == "localhost" || host.all { it.isDigit() || it == '.' }
}
