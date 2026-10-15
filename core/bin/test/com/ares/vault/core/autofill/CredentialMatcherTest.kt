package com.ares.vault.core.autofill

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CredentialMatcherTest {
    private val matcher = CredentialMatcher()
    private val realCert = "aa".repeat(32)
    private val fakeCert = "bb".repeat(32)
    private val installedCerts = mapOf("com.instagram.android" to realCert, "com.evil.clone" to fakeCert)
    private val certCheck = { pkg: String, stored: String -> installedCerts[pkg] == stored }

    private fun web(host: String) = ClientIdentity.Web(host, "com.android.chrome")

    @Test fun appRequiresPackageAndCert() {
        val stored = StoredIdentity("com.instagram.android", realCert, null)
        assertThat(matcher.match(stored, ClientIdentity.App("com.instagram.android", realCert), certCheck)).isEqualTo(MatchResult.MATCH)
        assertThat(matcher.match(stored, ClientIdentity.App("com.other.app", realCert), certCheck)).isEqualTo(MatchResult.NONE)
    }

    @Test fun samePackageDifferentSignerIsRejected() {
        // A sideloaded clone re-signed with another key, installed under the real package name.
        val stored = StoredIdentity("com.instagram.android", realCert, null)
        val clone = mapOf("com.instagram.android" to fakeCert)
        val r = matcher.match(stored, ClientIdentity.App("com.instagram.android", fakeCert)) { p, s -> clone[p] == s }
        assertThat(r).isEqualTo(MatchResult.CERT_MISMATCH)
    }

    @Test fun appEntryWithoutPinnedCertIsNotOffered() {
        val stored = StoredIdentity("com.instagram.android", null, null)
        assertThat(matcher.match(stored, ClientIdentity.App("com.instagram.android", realCert), certCheck))
            .isEqualTo(MatchResult.CERT_MISMATCH)
    }

    @Test fun webMatchesRegistrableDomain() {
        val stored = StoredIdentity(null, null, "example.co.ke")
        assertThat(matcher.match(stored, web("login.example.co.ke"), certCheck)).isEqualTo(MatchResult.MATCH)
        assertThat(matcher.match(stored, web("example.co.ke"), certCheck)).isEqualTo(MatchResult.MATCH)
        val sub = StoredIdentity(null, null, "accounts.example.co.ke")
        assertThat(matcher.match(sub, web("www.example.co.ke"), certCheck)).isEqualTo(MatchResult.MATCH)
    }

    @Test fun lookalikeDomainsDoNotMatch() {
        val stored = StoredIdentity(null, null, "example.com")
        listOf("example.com.evil.net", "examp1e.com", "example.co", "evil-example.com", "example.com.co.ke").forEach {
            assertThat(matcher.match(stored, web(it), certCheck)).isEqualTo(MatchResult.NONE)
        }
    }

    @Test fun sharedHostingSitesAreSeparate() {
        val stored = StoredIdentity(null, null, "alice.github.io")
        assertThat(matcher.match(stored, web("bob.github.io"), certCheck)).isEqualTo(MatchResult.NONE)
    }

    @Test fun extraUrlsAlsoMatch() {
        val stored = StoredIdentity(null, null, "google.com", listOf("youtube.com", "https://accounts.google.co.ke/x"))
        assertThat(matcher.match(stored, web("m.youtube.com"), certCheck)).isEqualTo(MatchResult.MATCH)
        assertThat(matcher.match(stored, web("accounts.google.co.ke"), certCheck)).isEqualTo(MatchResult.MATCH)
    }

    @Test fun webEntriesNeverMatchApps() {
        val stored = StoredIdentity(null, null, "instagram.com")
        assertThat(matcher.match(stored, ClientIdentity.App("com.instagram.android", realCert), certCheck)).isEqualTo(MatchResult.NONE)
    }

    @Test fun browserAllowlistRequiresRealSignature() {
        val list = BrowserAllowlist.default
        val chrome = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        assertThat(list.isTrustedBrowser("com.android.chrome", chrome)).isTrue()
        assertThat(list.isTrustedBrowser("com.android.chrome", fakeCert)).isFalse()
        assertThat(list.isTrustedBrowser("com.evil.browser", chrome)).isFalse()
        assertThat(list.isTrustedBrowser("org.mozilla.firefox", "a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04")).isTrue()
        assertThat(list.isTrustedBrowser("com.brave.browser", "9c2db70513515fdbfbbc585b3edf3d7123d4dc67c94ffd306361c1d79bbf18ac")).isTrue()
    }
}
