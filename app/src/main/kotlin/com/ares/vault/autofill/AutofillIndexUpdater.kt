package com.ares.vault.autofill

import com.ares.vault.data.AresLog
import com.ares.vault.data.autofill.AutofillIndexStore
import com.ares.vault.data.autofill.IndexEntry
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the locked-state index in sync with the vault while it is unlocked. Only emits
 * while unlocked, so locking never overwrites the index with an empty list.
 */
@Singleton
class AutofillIndexUpdater @Inject constructor(
    private val session: VaultSession,
    private val store: AutofillIndexStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        scope.launch {
            session.state.flatMapLatest { state ->
                if (state !is VaultState.Unlocked) {
                    emptyFlow()
                } else {
                    combine(state.database.credentials().observeAll(), state.database.settings().observe()) { items, settings ->
                        items to (settings?.showAccountsWhenLocked ?: true)
                    }.catch { /* DB closed while locking */ }
                }
            }.debounce(300).collect { (items, enabled) ->
                try {
                    if (enabled) {
                        store.write(
                            items.map {
                                IndexEntry(it.id, it.username, it.title, it.appPackage, it.appCertSha256, it.webDomain, it.extraUrls)
                            },
                        )
                    } else {
                        store.delete()
                    }
                } catch (e: Exception) {
                    AresLog.e("AutofillIndex", "Failed to update autofill index", e)
                }
            }
        }
    }
}
