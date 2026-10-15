package com.ares.vault.data.crypto

import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.Kdf
import com.ares.vault.core.crypto.SecureBytes
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** Argon2id via the native reference implementation bundled in Argon2kt. */
@Singleton
class Argon2KtKdf @Inject constructor() : Kdf {
    private val argon2 by lazy { Argon2Kt() }

    override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
        // We hand Argon2kt our own direct buffers (instead of the ByteArray overload,
        // which copies into buffers we can't reach) so every copy can be wiped.
        val passwordBuffer = direct(password)
        val saltBuffer = direct(salt)
        try {
            val result = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBuffer,
                salt = saltBuffer,
                tCostInIterations = params.iterations,
                mCostInKibibyte = params.memoryKiB,
                parallelism = params.parallelism,
                hashLengthInBytes = params.outputLength,
                version = Argon2Version.V13,
            )
            val raw = result.rawHash
            val out = ByteArray(raw.remaining()).also { raw.duplicate().get(it) }
            SecureBytes.wipe(raw)
            SecureBytes.wipe(result.encodedOutput)
            return out
        } finally {
            SecureBytes.wipe(passwordBuffer)
            SecureBytes.wipe(saltBuffer)
        }
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
}
