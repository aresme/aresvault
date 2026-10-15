package com.ares.vault.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmTest {
    private val key = SecureBytes.random(32)
    private val aad = "aad".toByteArray()
    private val plaintext = "the quick brown fox".toByteArray()

    @Test fun roundTrip() {
        val sealed = AesGcm.seal(key, plaintext, aad)
        assertThat(sealed.iv).hasLength(12)
        assertThat(AesGcm.open(key, sealed, aad)).isEqualTo(plaintext)
    }

    @Test fun ivsAreUnique() {
        val ivs = (1..200).map { SecureBytes.hex(AesGcm.seal(key, plaintext, aad).iv) }.toSet()
        assertThat(ivs).hasSize(200)
    }

    @Test fun wrongKeyFails() {
        val sealed = AesGcm.seal(key, plaintext, aad)
        assertThrows(AuthenticationFailedException::class.java) {
            AesGcm.open(SecureBytes.random(32), sealed, aad)
        }
    }

    @Test fun wrongAadFails() {
        val sealed = AesGcm.seal(key, plaintext, aad)
        assertThrows(AuthenticationFailedException::class.java) {
            AesGcm.open(key, sealed, "other".toByteArray())
        }
    }

    @Test fun everyCiphertextBitFlipIsDetected() {
        val sealed = AesGcm.seal(key, plaintext, aad)
        for (i in sealed.ciphertext.indices) {
            val ct = sealed.ciphertext.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows(AuthenticationFailedException::class.java) {
                AesGcm.open(key, Sealed(sealed.iv, ct), aad)
            }
        }
    }

    @Test fun ivTamperFails() {
        val sealed = AesGcm.seal(key, plaintext, aad)
        val iv = sealed.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }
        assertThrows(AuthenticationFailedException::class.java) {
            AesGcm.open(key, Sealed(iv, sealed.ciphertext), aad)
        }
    }

    @Test fun truncatedIvFails() {
        val sealed = AesGcm.seal(key, plaintext, aad)
        assertThrows(AuthenticationFailedException::class.java) {
            AesGcm.open(key, Sealed(sealed.iv.copyOf(8), sealed.ciphertext), aad)
        }
    }
}
