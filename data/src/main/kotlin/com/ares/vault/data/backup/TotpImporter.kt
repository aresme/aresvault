package com.ares.vault.data.backup

import com.ares.vault.core.otp.TotpSpec
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.repo.toDraft
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports 2FA accounts (e.g. from Google Authenticator). Each one is attached to an existing
 * login with the same username whose title/site mentions the issuer; otherwise it becomes a
 * new "2FA" entry without a password.
 */
@Singleton
class TotpImporter @Inject constructor(
    private val repository: CredentialRepository,
    private val session: com.ares.vault.data.session.VaultSession,
) {
    suspend fun import(specs: List<TotpSpec>): ImportReport {
        val all = repository.all()
        var attached = 0
        var added = 0
        var skipped = 0
        val dao = session.databaseOrNull()?.credentials() ?: throw CredentialRepository.VaultLockedException()
        val now = System.currentTimeMillis()
        for (spec in specs) {
            val uri = spec.toUri()
            if (all.any { it.totpUri != null && runCatching { TotpSpec.parseUri(it.totpUri) }.getOrNull()?.secret?.contentEquals(spec.secret) == true }) {
                skipped++
                continue
            }
            val target = all.firstOrNull { it.totpUri == null && matches(it, spec) }
            if (target != null) {
                repository.update(target.id, target.toDraft().copy(totpUri = uri))
                attached++
            } else {
                dao.insert(
                    CredentialEntity(
                        id = UUID.randomUUID().toString(),
                        title = spec.issuer.ifEmpty { spec.account.ifEmpty { "2FA" } },
                        username = spec.account,
                        password = "",
                        folder = "2FA",
                        totpUri = uri,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                added++
            }
        }
        return ImportReport(added = added, updated = attached, skipped = skipped)
    }

    private fun matches(c: CredentialEntity, spec: TotpSpec): Boolean {
        if (spec.account.isNotEmpty() && !c.username.equals(spec.account, ignoreCase = true)) return false
        val issuer = spec.issuer.lowercase().filter { it.isLetterOrDigit() }
        if (issuer.isEmpty()) return false
        val haystack = (c.title + " " + c.webDomain.orEmpty() + " " + c.appPackage.orEmpty()).lowercase().filter { it.isLetterOrDigit() }
        return issuer in haystack
    }
}
