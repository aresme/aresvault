package com.ares.vault.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.BouncyCastleArgon2Kdf
import com.ares.vault.core.crypto.VaultKeyHierarchy
import com.ares.vault.data.crypto.VaultHeaderStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultHeaderStoreTest {
    private val store = VaultHeaderStore(ApplicationProvider.getApplicationContext())

    @After fun clean() { store.delete() }

    @Test fun writeReadRoundTrip() {
        assertThat(store.read()).isNull()
        val header = VaultKeyHierarchy(BouncyCastleArgon2Kdf())
            .create("pw-1234567890".toCharArray(), Argon2Params(1024, 1, 1)).header
        store.write(header)
        assertThat(store.exists()).isTrue()
        assertThat(store.read()).isEqualTo(header)
    }
}
