package com.ares.vault.ui.vault

import com.ares.vault.data.db.CredentialEntity

enum class VaultFilter { ALL, FAVORITES, WEBSITES, APPS }

enum class VaultSort(val label: String) { NAME("Name A–Z"), RECENTLY_USED("Recently used"), RECENTLY_ADDED("Recently added") }

/** What the home list shows, top to bottom. */
sealed interface VaultRow {
    val key: String

    data class Header(val title: String, val letter: Char? = null) : VaultRow {
        override val key get() = "h:$title"
    }

    /** Horizontal strip of favourites (home view only). */
    data class Favorites(val items: List<CredentialEntity>) : VaultRow {
        override val key get() = "favorites"
    }

    data class Entry(val item: CredentialEntity, val indented: Boolean = false) : VaultRow {
        override val key get() = "e:${item.id}" + if (indented) ":g" else ""
    }

    /** Several accounts for the same site/app, collapsed into one row. */
    data class SiteGroup(val siteKey: String, val title: String, val items: List<CredentialEntity>, val expanded: Boolean) : VaultRow {
        override val key get() = "g:$siteKey"
    }
}

/**
 * Arranges the vault instead of one flat list:
 * - home view (no search, "All", sorted by name): favourites strip, "Recently used", then
 *   A–Z sections with accounts for the same site grouped together;
 * - filters (favourites / websites / apps / a folder) and other sorts give a focused list;
 * - search shows flat ranked matches.
 */
object VaultGrouping {
    const val RECENT_COUNT = 5

    fun build(
        items: List<CredentialEntity>,
        query: String,
        filter: VaultFilter,
        folder: String?,
        sort: VaultSort,
        expanded: Set<String>,
        appLabel: (String) -> String?,
        siteKey: (CredentialEntity) -> String?,
    ): List<VaultRow> {
        val filtered = items.filter { e ->
            when (filter) {
                VaultFilter.ALL -> true
                VaultFilter.FAVORITES -> e.favorite
                VaultFilter.WEBSITES -> e.webDomain != null
                VaultFilter.APPS -> e.appPackage != null
            } && (folder == null || e.folder == folder)
        }

        if (query.isNotBlank()) {
            return VaultSearch.filter(filtered, query, appLabel).sortedWith(byName()).map { VaultRow.Entry(it) }
        }

        if (sort != VaultSort.NAME) {
            val sorted = when (sort) {
                VaultSort.RECENTLY_USED -> filtered.sortedWith(compareByDescending<CredentialEntity> { it.lastUsedAt ?: 0L }.then(byName()))
                VaultSort.RECENTLY_ADDED -> filtered.sortedWith(compareByDescending<CredentialEntity> { it.createdAt }.then(byName()))
                VaultSort.NAME -> filtered
            }
            return sorted.map { VaultRow.Entry(it) }
        }

        val rows = mutableListOf<VaultRow>()
        val home = filter == VaultFilter.ALL && folder == null
        if (home) {
            val favorites = filtered.filter { it.favorite }.sortedWith(byName())
            if (favorites.isNotEmpty()) rows += VaultRow.Favorites(favorites)
            val recent = filtered.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(RECENT_COUNT)
            if (recent.isNotEmpty()) {
                rows += VaultRow.Header("Recently used")
                recent.forEach { rows += VaultRow.Entry(it) }
            }
        }

        // Group accounts of the same site, then section the groups by first letter.
        val groups = filtered.groupBy { siteKey(it) ?: "id:${it.id}" }.values
            .map { members -> members.sortedWith(byName()) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { groupTitle(it, appLabel, siteKey) })
        var currentLetter: Char? = null
        for (members in groups) {
            val title = groupTitle(members, appLabel, siteKey)
            val letter = sectionLetter(title)
            if (letter != currentLetter) {
                currentLetter = letter
                rows += VaultRow.Header(letter.toString(), letter)
            }
            if (members.size == 1) {
                rows += VaultRow.Entry(members.single())
            } else {
                val key = siteKey(members.first()) ?: members.first().id
                val isExpanded = key in expanded
                rows += VaultRow.SiteGroup(key, title, members, isExpanded)
                if (isExpanded) members.forEach { rows += VaultRow.Entry(it, indented = true) }
            }
        }
        return rows
    }

    /** Letters that have a section, for the side scrubber. */
    fun letters(rows: List<VaultRow>): List<Char> = rows.mapNotNull { (it as? VaultRow.Header)?.letter }

    /** Folders in use, for filter chips. */
    fun folders(items: List<CredentialEntity>): List<String> =
        items.mapNotNull { it.folder?.takeIf(String::isNotBlank) }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** A single entry keeps its own title; a group is named after the app or the site itself. */
    private fun groupTitle(members: List<CredentialEntity>, appLabel: (String) -> String?, siteKey: (CredentialEntity) -> String?): String {
        val first = members.first()
        if (members.size == 1) return first.title
        return first.appPackage?.let { appLabel(it) } ?: siteKey(first) ?: first.title
    }

    private fun sectionLetter(title: String): Char {
        val c = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
        return if (c.isLetter()) c else '#'
    }

    private fun byName() = compareBy<CredentialEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.username }
}
