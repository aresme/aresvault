package com.ares.vault.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies the reference KDF against the RFC 9106 §5.3 Argon2id test vector. */
class BouncyCastleArgon2KdfTest {
    @Test fun rfc9106Argon2idVector() {
        val out = BouncyCastleArgon2Kdf().derive(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            params = Argon2Params(memoryKiB = 32, iterations = 3, parallelism = 4),
            secret = ByteArray(8) { 0x03 },
            additional = ByteArray(12) { 0x04 },
        )
        assertThat(SecureBytes.hex(out))
            .isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")
    }
}
