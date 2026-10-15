package com.ares.vault.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DomainsTest {
    @Test fun normalises() {
        assertThat(Domains.normalizeHost("https://Login.Example.co.ke/path?q=1")).isEqualTo("login.example.co.ke")
        assertThat(Domains.normalizeHost("example.com")).isEqualTo("example.com")
        assertThat(Domains.normalizeHost("  www.github.com:443/x ")).isEqualTo("www.github.com")
        assertThat(Domains.normalizeHost("http://user:pw@example.org")).isEqualTo("example.org")
        assertThat(Domains.normalizeHost("bücher.de")).isEqualTo("xn--bcher-kva.de")
        assertThat(Domains.normalizeHost("example.com.")).isEqualTo("example.com")
    }

    @Test fun rejectsGarbage() {
        listOf("", "   ", "not a domain", "nodot", "exa mple.com", "-bad-.com", "https://", "a..b")
            .forEach { assertThat(Domains.normalizeHost(it)).isNull() }
    }
}
