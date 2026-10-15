package com.ares.vault.ui

import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.ui.vault.VaultSearch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultSearchTest {
    private fun entry(title: String, user: String = "", domain: String? = null, pkg: String? = null, folder: String? = null) =
        CredentialEntity(
            id = title, title = title, username = user, password = "x", webDomain = domain,
            appPackage = pkg, folder = folder, createdAt = 0, updatedAt = 0,
        )

    private val items = listOf(
        entry("GitHub", "alice@example.com", domain = "github.com"),
        entry("Insta", "alice_k", pkg = "com.instagram.android"),
        entry("Bank", "12345", domain = "equitybank.co.ke", folder = "Finance"),
    )
    private val labels = mapOf("com.instagram.android" to "Instagram")

    private fun search(q: String) = VaultSearch.filter(items, q) { labels[it] }.map { it.title }

    @Test fun matchesAcrossFields() {
        assertThat(search("")).hasSize(3)
        assertThat(search("github")).containsExactly("GitHub")
        assertThat(search("ALICE")).containsExactly("GitHub", "Insta")
        assertThat(search("instagram")).containsExactly("Insta") // app label
        assertThat(search("co.ke")).containsExactly("Bank")
        assertThat(search("finance")).containsExactly("Bank")
        assertThat(search("alice github")).containsExactly("GitHub") // all terms must match
        assertThat(search("nothing")).isEmpty()
    }
}
