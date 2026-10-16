package com.ares.vault.core.backup

import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.AuthenticationFailedException
import com.ares.vault.core.crypto.BouncyCastleArgon2Kdf
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {
    private val codec = BackupCodec(BouncyCastleArgon2Kdf())
    private val fast = Argon2Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val payload = BackupPayload(
        exportedAt = 42,
        entries = listOf(
            BackupEntry(
                id = "1", title = "GitHub", username = "alice", password = "pässwörd-🔑", webDomain = "github.com",
                extraUrls = listOf("gist.github.com"), createdAt = 1, updatedAt = 2,
                history = listOf(BackupHistory("old", 1)),
            ),
            BackupEntry(id = "2", title = "Insta", username = "a", password = "b", appPackage = "com.instagram.android", appCertSha256 = "ab".repeat(32), createdAt = 3, updatedAt = 4),
        ),
    )

    @Test fun roundTrip() {
        val bytes = codec.encrypt(payload, "backup-password-123".toCharArray(), fast)
        assertThat(codec.decrypt(bytes, "backup-password-123".toCharArray())).isEqualTo(payload)
        val text = bytes.decodeToString()
        assertThat(text).doesNotContain("github")
        assertThat(text).doesNotContain("alice")
    }

    @Test fun defaultParamsRoundTrip() {
        val bytes = codec.encrypt(payload, "backup-password-123".toCharArray())
        assertThat(codec.decrypt(bytes, "backup-password-123".toCharArray()).entries).hasSize(2)
    }

    @Test fun wrongPasswordFails() {
        val bytes = codec.encrypt(payload, "backup-password-123".toCharArray(), fast)
        assertThrows(AuthenticationFailedException::class.java) { codec.decrypt(bytes, "nope".toCharArray()) }
    }

    @Test fun tamperedParamsOrCiphertextFail() {
        val text = codec.encrypt(payload, "pw-1234567890".toCharArray(), fast).decodeToString()
        val downgraded = text.replace("\"iterations\":1", "\"iterations\":2")
        assertThrows(AuthenticationFailedException::class.java) { codec.decrypt(downgraded.toByteArray(), "pw-1234567890".toCharArray()) }
        val ct = Regex("\"ciphertext\":\"(.)").find(text)!!.groupValues[1]
        val flipped = text.replaceFirst("\"ciphertext\":\"$ct", "\"ciphertext\":\"" + (if (ct == "A") "B" else "A"))
        assertThrows(AuthenticationFailedException::class.java) { codec.decrypt(flipped.toByteArray(), "pw-1234567890".toCharArray()) }
    }

    @Test fun garbageAndHostileParamsRejected() {
        assertThrows(InvalidBackupException::class.java) { codec.decrypt("hello".toByteArray(), "x".toCharArray()) }
        val text = codec.encrypt(payload, "pw-1234567890".toCharArray(), fast).decodeToString()
        val huge = text.replace("\"memoryKiB\":1024", "\"memoryKiB\":99999999")
        assertThrows(InvalidBackupException::class.java) { codec.decrypt(huge.toByteArray(), "pw-1234567890".toCharArray()) }
        val wrongFormat = text.replace("aresvault-backup", "other")
        assertThrows(InvalidBackupException::class.java) { codec.decrypt(wrongFormat.toByteArray(), "pw-1234567890".toCharArray()) }
    }
}
