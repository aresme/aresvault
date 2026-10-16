package com.ares.vault.autofill

import android.service.autofill.SaveRequest
import com.ares.vault.apps.AppInfoProvider
import com.ares.vault.core.autofill.ClientIdentity
import com.ares.vault.core.autofill.CredentialMatcher
import com.ares.vault.data.AresLog
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.repo.CredentialDraft
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.repo.toDraft
import com.ares.vault.data.session.AutoLockController
import com.ares.vault.data.session.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A login captured from a submitted form. Lives in memory only — never written to disk. */
class PendingSave(val identity: ClientIdentity, val username: String, val password: String, val createdAt: Long = System.currentTimeMillis())

/** Hands pending saves to [SaveActivity] by token, so no secret travels in an Intent. */
@Singleton
class PendingSaveStore @Inject constructor() {
    private val items = ConcurrentHashMap<String, PendingSave>()

    fun put(save: PendingSave): String {
        val now = System.currentTimeMillis()
        items.entries.removeIf { now - it.value.createdAt > TTL_MS }
        return UUID.randomUUID().toString().also { items[it] = save }
    }

    fun take(token: String): PendingSave? = items.remove(token)?.takeIf { System.currentTimeMillis() - it.createdAt <= TTL_MS }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}

/** What to do with a captured login. */
sealed interface SaveDecision {
    data object AlreadySaved : SaveDecision
    data object New : SaveDecision
    class Update(val existing: CredentialEntity) : SaveDecision
}

@Singleton
class SaveHandler @Inject constructor(
    private val contexts: FillContextFactory,
    private val finder: CandidateFinder,
    private val repository: CredentialRepository,
    private val session: VaultSession,
    private val autoLock: AutoLockController,
    private val apps: AppInfoProvider,
) {
    sealed interface Outcome {
        class Saved(val title: String) : Outcome
        /** Needs [SaveActivity]: vault locked, or an existing entry to update. */
        class NeedsUi(val token: String) : Outcome
        data object Ignored : Outcome
    }

    private val matcher by lazy { CredentialMatcher() }

    /**
     * Extracts username/password across all fill contexts (a username page followed by a
     * password page arrives as several contexts) using the *final* page's identity.
     */
    fun extract(request: SaveRequest): PendingSave? {
        var username: String? = null
        var password: String? = null
        var identity: ClientIdentity? = null
        for (fc in request.fillContexts) {
            val ctx = contexts.create(fc.structure, null) ?: continue
            identity = ctx.identity
            ctx.parsed.field(ctx.analysis.username)?.let(StructureParser::textValue)?.takeIf { it.isNotBlank() }?.let { username = it.trim() }
            val pwId = ctx.analysis.password ?: ctx.analysis.newPasswords.firstOrNull()
            ctx.parsed.field(pwId)?.let(StructureParser::textValue)?.takeIf { it.isNotEmpty() }?.let { password = it }
        }
        val id = identity ?: return null
        val pw = password ?: return null
        return PendingSave(id, username.orEmpty(), pw)
    }

    suspend fun handle(save: PendingSave, store: PendingSaveStore): Outcome {
        withContext(Dispatchers.Main) { autoLock.lockIfExpired() }
        if (!session.isUnlocked) return Outcome.NeedsUi(store.put(save))
        return when (val d = decide(save)) {
            SaveDecision.AlreadySaved -> Outcome.Ignored
            SaveDecision.New -> Outcome.Saved(create(save))
            is SaveDecision.Update -> Outcome.NeedsUi(store.put(save))
        }
    }

    /** Same identity + same username → update; same password too → nothing to do. */
    suspend fun decide(save: PendingSave): SaveDecision {
        val existing = finder.unlocked(save.identity).mapNotNull { it.entity }
        val same = existing.firstOrNull { it.username.equals(save.username, ignoreCase = true) }
            ?: existing.singleOrNull()?.takeIf { save.username.isEmpty() }
        return when {
            same == null -> SaveDecision.New
            same.password == save.password -> SaveDecision.AlreadySaved
            else -> SaveDecision.Update(same)
        }
    }

    /** Creates an entry titled after the app label or site. Returns the title. */
    suspend fun create(save: PendingSave): String {
        val draft = when (val i = save.identity) {
            is ClientIdentity.App -> CredentialDraft(
                title = apps.label(i.packageName) ?: i.packageName,
                username = save.username,
                password = save.password,
                appPackage = i.packageName,
                appCertSha256 = i.certSha256,
            )
            is ClientIdentity.Web -> CredentialDraft(
                title = matcher.siteKey(i.host) ?: i.host,
                username = save.username,
                password = save.password,
                webDomain = i.host,
            )
        }
        repository.create(draft)
        AresLog.i("Save", "Saved new login for ${draft.title}")
        return draft.title
    }

    /** Replaces the password; the old one moves into the entry's history. */
    suspend fun update(existing: CredentialEntity, save: PendingSave) {
        repository.update(existing.id, existing.toDraft().copy(password = save.password))
        AresLog.i("Save", "Updated password for ${existing.title}")
    }
}
