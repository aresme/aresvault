package com.ares.vault.ui.vault

import com.ares.vault.data.db.CredentialEntity

/** In-memory search (vaults are small; the DB is encrypted so LIKE queries gain nothing). */
object VaultSearch {
    fun filter(
        items: List<CredentialEntity>,
        query: String,
        appLabel: (String) -> String?,
    ): List<CredentialEntity> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return items
        return items.filter { c ->
            val haystack = buildString {
                append(c.title).append(' ').append(c.username).append(' ')
                c.webDomain?.let { append(it).append(' ') }
                c.appPackage?.let { append(it).append(' ').append(appLabel(it).orEmpty()).append(' ') }
                c.folder?.let { append(it).append(' ') }
                c.extraUrls.forEach { append(it).append(' ') }
            }.lowercase()
            terms.all { it in haystack }
        }
    }
}
