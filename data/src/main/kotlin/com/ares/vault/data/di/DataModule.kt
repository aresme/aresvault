package com.ares.vault.data.di

import com.ares.vault.core.crypto.Kdf
import com.ares.vault.core.policy.BruteForceGuard
import com.ares.vault.core.policy.LockoutStore
import com.ares.vault.core.policy.VaultClock
import com.ares.vault.data.crypto.Argon2KtKdf
import com.ares.vault.data.session.AndroidVaultClock
import com.ares.vault.data.session.PrefsLockoutStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindKdf(impl: Argon2KtKdf): Kdf

    @Binds
    abstract fun bindClock(impl: AndroidVaultClock): VaultClock

    @Binds
    abstract fun bindLockoutStore(impl: PrefsLockoutStore): LockoutStore

    companion object {
        @Provides
        @Singleton
        fun provideBruteForceGuard(store: LockoutStore, clock: VaultClock) = BruteForceGuard(store, clock)
    }
}
