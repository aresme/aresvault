package com.ares.vault.debug

import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets instrumented tests reach app singletons (debug/staging builds only). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestHooks {
    fun vaultManager(): VaultManager
    fun credentials(): CredentialRepository
    fun settings(): SettingsRepository
}
