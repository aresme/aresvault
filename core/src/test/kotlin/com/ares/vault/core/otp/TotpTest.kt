package com.ares.vault.core.otp

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class TotpTest {
    private val sha1 = "12345678901234567890".toByteArray()
    private val sha256 = "12345678901234567890123456789012".toByteArray()
    private val sha512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    /** RFC 6238 Appendix B test vectors (8 digits). */
    @Test fun rfc6238Vectors() {
        val vectors = listOf(
            59L to listOf("94287082", "46119246", "90693936"),
            1111111109L to listOf("07081804", "68084774", "25091201"),
            1111111111L to listOf("14050471", "67062674", "99943326"),
            1234567890L to listOf("89005924", "91819424", "93441116"),
            2000000000L to listOf("69279037", "90698825", "38618901"),
            20000000000L to listOf("65353130", "77737706", "47863826"),
        )
        for ((t, codes) in vectors) {
            assertThat(TotpSpec(sha1, algorithm = OtpAlgorithm.SHA1, digits = 8).code(t * 1000)).isEqualTo(codes[0])
            assertThat(TotpSpec(sha256, algorithm = OtpAlgorithm.SHA256, digits = 8).code(t * 1000)).isEqualTo(codes[1])
            assertThat(TotpSpec(sha512, algorithm = OtpAlgorithm.SHA512, digits = 8).code(t * 1000)).isEqualTo(codes[2])
        }
    }

    /** RFC 4226 Appendix D HOTP vectors (6 digits). */
    @Test fun rfc4226Vectors() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, code -> assertThat(TotpSpec(sha1).hotp(counter.toLong())).isEqualTo(code) }
    }

    @Test fun countdown() {
        val spec = TotpSpec(sha1)
        assertThat(spec.secondsRemaining(0)).isEqualTo(30)
        assertThat(spec.secondsRemaining(29_500)).isEqualTo(1)
        assertThat(spec.secondsRemaining(31_000)).isEqualTo(29)
    }

    /** RFC 4648 §10 Base32 vectors. */
    @Test fun base32Vectors() {
        val vectors = mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        vectors.filterKeys { it.isNotEmpty() }.forEach { (plain, enc) ->
            assertThat(Base32.encode(plain.toByteArray())).isEqualTo(enc)
            assertThat(Base32.decode(enc).decodeToString()).isEqualTo(plain)
        }
        assertThat(Base32.decode("mzxw 6ytb-oi==").decodeToString()).isEqualTo("foobar")
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZX1") }
    }

    @Test fun parsesOtpauthUris() {
        val s = TotpSpec.parseUri("otpauth://totp/GitHub:kiplangat?secret=JBSWY3DPEHPK3PXP&issuer=GitHub")
        assertThat(s.issuer).isEqualTo("GitHub")
        assertThat(s.account).isEqualTo("kiplangat")
        assertThat(s.digits).isEqualTo(6)
        val full = TotpSpec.parseUri("otpauth://totp/ACME%20Co:john%40example.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60")
        assertThat(full.issuer).isEqualTo("ACME Co")
        assertThat(full.account).isEqualTo("john@example.com")
        assertThat(full.algorithm).isEqualTo(OtpAlgorithm.SHA256)
        assertThat(full.digits).isEqualTo(8)
        assertThat(full.periodSeconds).isEqualTo(60)
        assertThat(TotpSpec.parseUri(full.toUri())).isEqualTo(full)
        assertThat(TotpSpec.parseUri(s.toUri())).isEqualTo(s)
    }

    @Test fun rejectsBadUris() {
        listOf(
            "https://example.com",
            "otpauth://hotp/X?secret=JBSWY3DPEHPK3PXP&counter=1",
            "otpauth://totp/X",
            "otpauth://totp/X?secret=JBSWY3DP", // too short
            "otpauth://totp/X?secret=JBSWY3DPEHPK3PXP&algorithm=MD5",
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { TotpSpec.parseUri(bad) } }
    }

    @Test fun decodesGoogleAuthenticatorExport() {
        // Hand-built MigrationPayload: one TOTP (GitHub:kip, SHA1, 6 digits) + one HOTP, batch 0 of 1.
        fun varint(v: Int): ByteArray {
            var x = v
            val out = mutableListOf<Byte>()
            while (x >= 0x80) { out += ((x and 0x7F) or 0x80).toByte(); x = x ushr 7 }
            out += x.toByte()
            return out.toByteArray()
        }
        fun field(n: Int, bytes: ByteArray) = varint((n shl 3) or 2) + varint(bytes.size) + bytes
        fun intField(n: Int, v: Int) = varint(n shl 3) + varint(v)
        val secret = Base32.decode("JBSWY3DPEHPK3PXP")
        val totp = field(1, secret) + field(2, "GitHub:kip".toByteArray()) + field(3, "GitHub".toByteArray()) +
            intField(4, 1) + intField(5, 1) + intField(6, 2)
        val hotp = field(1, secret) + field(2, "Old".toByteArray()) + intField(6, 1)
        val payload = field(1, totp) + field(1, hotp) + intField(2, 1) + intField(3, 1) + intField(4, 0)
        val uri = "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")

        val batch = GoogleAuthenticatorMigration.parse(uri)
        assertThat(batch.accounts).hasSize(1)
        assertThat(batch.skippedHotp).isEqualTo(1)
        val a = batch.accounts.single()
        assertThat(a.issuer).isEqualTo("GitHub")
        assertThat(a.account).isEqualTo("kip")
        assertThat(a.secret).isEqualTo(secret)
        assertThat(batch.batchSize).isEqualTo(1)
    }
}
