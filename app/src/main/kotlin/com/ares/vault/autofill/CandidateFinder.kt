package com.ares.vault.autofill

import com.ares.vault.core.autofill.ClientIdentity
import com.ares.vault.core.autofill.CredentialMatcher
import com.ares.vault.core.autofill.MatchResult
import com.ares.vault.core.autofill.StoredIdentity
import com.ares.vault.data.AresLog
import com.ares.vault.data.autofill.AutofillIndexStore
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.repo.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

/** An account to offer. [entity] is present only when the vault is unlocked. */
class Candidate(val id: String, val username: String, val title: String, val entity: CredentialEntity?, val hasTotp: Boolean = entity?.totpUri != null)

@Singleton
class CandidateFinder @Inject constructor(
    private val repository: CredentialRepository,
    private val index: AutofillIndexStore,
    private val identities: IdentityResolver,
) {
    private val matcher by lazy { CredentialMatcher() }

    suspend fun unlocked(client: ClientIdentity): List<Candidate> =
        repository.all()
            .filter { matches(StoredIdentity(it.appPackage, it.appCertSha256, it.webDomain, it.extraUrls), client, it.id) }
            .sortedWith(compareByDescending<CredentialEntity> { it.lastUsedAt ?: 0 }.thenBy { it.title.lowercase() })
            .map { Candidate(it.id, it.username, it.title, it) }

    /** Null when no locked-state index exists (setting off or never unlocked). */
    fun locked(client: ClientIdentity): List<Candidate>? =
        index.read()
            ?.filter { matches(StoredIdentity(it.appPackage, it.appCertSha256, it.webDomain, it.extraUrls), client, it.id) }
            ?.map { Candidate(it.id, it.username, it.title, null, it.hasTotp) }

    fun matches(stored: StoredIdentity, client: ClientIdentity, idForLog: String = "?"): Boolean =
        when (matcher.match(stored, client, identities::certMatches)) {
            MatchResult.MATCH -> true
            MatchResult.NONE -> false
            MatchResult.CERT_MISMATCH -> {
                // Spec §4.4: same package, different signer → show nothing and warn.
                AresLog.w("Autofill", "Signature mismatch for ${(client as? ClientIdentity.App)?.packageName} (entry $idForLog); not offering")
                false
            }
        }
}
