package com.ares.vault.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

class SecureBytesTest {
    @Test fun utf8MatchesStringEncoding() {
        val s = "pässwörd 🔑 ok"
        assertThat(SecureBytes.utf8(s.toCharArray())).isEqualTo(s.toByteArray(Charsets.UTF_8))
    }

    @Test fun utf8RejectsUnpairedSurrogate() {
        assertThrows(CharacterCodingException::class.java) {
            SecureBytes.utf8(charArrayOf('a', '\uD83D'))
        }
    }

    @Test fun wipeZeroesArraysAndDirectBuffers() {
        val bytes = byteArrayOf(1, 2, 3)
        val chars = charArrayOf('a', 'b')
        val buffer = ByteBuffer.allocateDirect(4).put(byteArrayOf(9, 9, 9, 9))
        SecureBytes.wipe(bytes)
        SecureBytes.wipe(chars)
        SecureBytes.wipe(buffer)
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
        assertThat(chars.all { it == '\u0000' }).isTrue()
        assertThat((0 until 4).all { buffer.get(it) == 0.toByte() }).isTrue()
    }

    @Test fun sqlCipherRawKeyFormat() {
        val dek = ByteArray(32) { it.toByte() }
        val key = SqlCipherKey.rawKey(dek).toString(Charsets.US_ASCII)
        assertThat(key).isEqualTo("x'" + SecureBytes.hex(dek) + "'")
    }
}
