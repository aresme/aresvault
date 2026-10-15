package com.ares.vault.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultKeyHierarchyTest {
    // Small params keep the suite fast; one test exercises the real defaults.
    private val fast = Argon2Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val keys = VaultKeyHierarchy(BouncyCastleArgon2Kdf())
    private fun pw(s: String) = s.toCharArray()

    @Test fun createThenUnlockReturnsSameDek() {
        val created = keys.create(pw("correct horse battery"), fast)
        assertThat(created.dek).hasLength(32)
        assertThat(keys.unlock(created.header, pw("correct horse battery"))).isEqualTo(created.dek)
    }

    @Test fun defaultParamsRoundTrip() {
        val created = keys.create(pw("päßwörd-ünïcode-🔑"), Argon2Params.DEFAULT)
        assertThat(keys.unlock(created.header, pw("päßwörd-ünïcode-🔑"))).isEqualTo(created.dek)
    }

    @Test fun headerSurvivesSerialisation() {
        val created = keys.create(pw("correct horse battery"), fast)
        val decoded = VaultHeader.decode(created.header.encode())
        assertThat(decoded).isEqualTo(created.header)
        assertThat(keys.unlock(decoded, pw("correct horse battery"))).isEqualTo(created.dek)
    }

    @Test fun headerContainsNoSecrets() {
        val created = keys.create(pw("correct horse battery"), fast)
        val json = created.header.encode().toString(Charsets.UTF_8)
        assertThat(json).doesNotContain("correct horse")
        assertThat(json).doesNotContain(SecureBytes.hex(created.dek))
        assertThat(json).doesNotContain(java.util.Base64.getEncoder().encodeToString(created.dek))
    }

    @Test fun wrongPasswordFails() {
        val created = keys.create(pw("correct horse battery"), fast)
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(created.header, pw("correct horse batterY"))
        }
    }

    @Test fun twoVaultsWithSamePasswordHaveDifferentSaltsAndDeks() {
        val a = keys.create(pw("same password here"), fast)
        val b = keys.create(pw("same password here"), fast)
        assertThat(a.header.salt).isNotEqualTo(b.header.salt)
        assertThat(a.dek).isNotEqualTo(b.dek)
    }

    @Test fun tamperedCiphertextFails() {
        val h = keys.create(pw("correct horse battery"), fast).header
        val ct = h.passwordSlot.ciphertext.copyOf().also { it[3] = (it[3] + 1).toByte() }
        val tampered = h.copy(passwordSlot = Sealed(h.passwordSlot.iv, ct))
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(tampered, pw("correct horse battery"))
        }
    }

    @Test fun tamperedIvFails() {
        val h = keys.create(pw("correct horse battery"), fast).header
        val iv = h.passwordSlot.iv.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val tampered = h.copy(passwordSlot = Sealed(iv, h.passwordSlot.ciphertext))
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(tampered, pw("correct horse battery"))
        }
    }

    @Test fun tamperedSaltFails() {
        val h = keys.create(pw("correct horse battery"), fast).header
        val salt = h.salt.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(h.copy(salt = salt), pw("correct horse battery"))
        }
    }

    @Test fun downgradedKdfParamsFail() {
        // An attacker lowering the cost params must not get a working header.
        val params = Argon2Params(memoryKiB = 2048, iterations = 2, parallelism = 1)
        val h = keys.create(pw("correct horse battery"), params).header
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(h.copy(kdf = params.copy(iterations = 1)), pw("correct horse battery"))
        }
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(h.copy(kdf = params.copy(memoryKiB = 1024)), pw("correct horse battery"))
        }
    }

    @Test fun changePasswordKeepsDekAndBiometricSlot() {
        val created = keys.create(pw("old password 123"), fast)
        val bio = Sealed(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val withBio = created.header.copy(biometricSlot = bio)
        val changed = keys.changePassword(withBio, created.dek, pw("new password 456"), fast)

        assertThat(changed.salt).isNotEqualTo(created.header.salt)
        assertThat(changed.biometricSlot).isEqualTo(bio)
        assertThat(keys.unlock(changed, pw("new password 456"))).isEqualTo(created.dek)
        assertThrows(AuthenticationFailedException::class.java) {
            keys.unlock(changed, pw("old password 123"))
        }
    }

    @Test fun corruptHeaderIsRejected() {
        assertThrows(InvalidHeaderException::class.java) {
            VaultHeader.decode("{not json".toByteArray())
        }
        val json = keys.create(pw("correct horse battery"), fast).header.encode()
            .toString(Charsets.UTF_8)
        assertThrows(InvalidHeaderException::class.java) {
            VaultHeader.decode(json.replace("\"version\":1", "\"version\":99").toByteArray())
        }
        assertThrows(InvalidHeaderException::class.java) {
            VaultHeader.decode(json.replace("aresvault-header", "other").toByteArray())
        }
    }
}
