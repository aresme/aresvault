package com.ares.vault.ui

import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.ui.vault.VaultFilter
import com.ares.vault.ui.vault.VaultGrouping
import com.ares.vault.ui.vault.VaultRow
import com.ares.vault.ui.vault.VaultSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultGroupingTest {
    private fun e(
        title: String, user: String = "", domain: String? = null, pkg: String? = null,
        fav: Boolean = false, used: Long? = null, created: Long = 0, folder: String? = null,
    ) = CredentialEntity(
        id = "$title/$user", title = title, username = user, password = "x", webDomain = domain, appPackage = pkg,
        favorite = fav, lastUsedAt = used, createdAt = created, updatedAt = 0, folder = folder,
    )

    private val items = listOf(
        e("Google", "work@x.com", domain = "accounts.google.com", used = 50),
        e("Gmail", "me@x.com", domain = "mail.google.com", fav = true),
        e("Amazon", "a", domain = "amazon.com", created = 9),
        e("Instagram", "insta", pkg = "com.instagram.android", used = 90, folder = "Social"),
        e("bank", "kip", domain = "equitybank.co.ke", fav = true, folder = "Finance"),
        e("9gag", "z", domain = "9gag.com"),
    )
    private fun site(c: CredentialEntity) = c.appPackage ?: c.webDomain?.split('.')?.takeLast(2)?.joinToString(".")

    private fun build(query: String = "", filter: VaultFilter = VaultFilter.ALL, folder: String? = null, sort: VaultSort = VaultSort.NAME, expanded: Set<String> = emptySet()) =
        VaultGrouping.build(items, query, filter, folder, sort, expanded, { if (it == "com.instagram.android") "Instagram" else null }, ::site)

    @Test fun homeShowsFavoritesRecentThenSections() {
        val rows = build()
        assertThat((rows[0] as VaultRow.Favorites).items.map { it.title }).containsExactly("bank", "Gmail").inOrder()
        assertThat((rows[1] as VaultRow.Header).title).isEqualTo("Recently used")
        assertThat((rows[2] as VaultRow.Entry).item.title).isEqualTo("Instagram")
        assertThat((rows[3] as VaultRow.Entry).item.title).isEqualTo("Google")
        assertThat(VaultGrouping.letters(rows)).containsExactly('#', 'A', 'B', 'G', 'I').inOrder()
    }

    @Test fun sameSiteAccountsAreGrouped() {
        val group = build().filterIsInstance<VaultRow.SiteGroup>().single()
        assertThat(group.title).isEqualTo("google.com")
        assertThat(group.items.map { it.title }).containsExactly("Gmail", "Google").inOrder()
        assertThat(group.expanded).isFalse()
        val expanded = build(expanded = setOf("google.com"))
        val idx = expanded.indexOfFirst { it is VaultRow.SiteGroup }
        assertThat((expanded[idx + 1] as VaultRow.Entry).indented).isTrue()
        assertThat((expanded[idx + 2] as VaultRow.Entry).indented).isTrue()
    }

    @Test fun filtersAndFolders() {
        fun titles(rows: List<VaultRow>) = rows.flatMap {
            when (it) {
                is VaultRow.Entry -> listOf(it.item.title)
                is VaultRow.SiteGroup -> it.items.map { i -> i.title }
                else -> emptyList()
            }
        }
        assertThat(titles(build(filter = VaultFilter.APPS))).containsExactly("Instagram")
        assertThat(titles(build(filter = VaultFilter.FAVORITES))).containsExactly("bank", "Gmail")
        assertThat(titles(build(folder = "Finance"))).containsExactly("bank")
        assertThat(build(filter = VaultFilter.WEBSITES).none { it is VaultRow.Favorites }).isTrue()
        assertThat(VaultGrouping.folders(items)).containsExactly("Finance", "Social").inOrder()
    }

    @Test fun sortsAndSearchAreFlat() {
        val used = build(sort = VaultSort.RECENTLY_USED).map { (it as VaultRow.Entry).item.title }
        assertThat(used.take(2)).containsExactly("Instagram", "Google").inOrder()
        val added = build(sort = VaultSort.RECENTLY_ADDED).map { (it as VaultRow.Entry).item.title }
        assertThat(added.first()).isEqualTo("Amazon")
        val search = build(query = "google")
        assertThat(search.all { it is VaultRow.Entry }).isTrue()
        assertThat(search.map { (it as VaultRow.Entry).item.title }).containsExactly("Gmail", "Google").inOrder()
    }
}
