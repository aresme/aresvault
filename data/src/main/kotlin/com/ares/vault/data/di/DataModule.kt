package com.ares.vault.data.di

import com.ares.vault.core.crypto.Kdf
import com.ares.vault.data.crypto.Argon2KtKdf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindKdf(impl: Argon2KtKdf): Kdf
}
