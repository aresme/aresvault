package com.ares.vault.core.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordCsvTest {
    private val cert = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private val certB64 = "8P1sW0EPJcslw7UzRsiXL64w-O50Ed-RBICta y1g24M".replace(" ", "")

    @Test fun parsesRfc4180() {
        val rows = Csv.parse("﻿a,\"b,c\",\"say \"\"hi\"\"\"\r\n\"multi\nline\",,x\n\n")
        assertThat(rows).containsExactly(listOf("a", "b,c", "say \"hi\""), listOf("multi\nline", "", "x")).inOrder()
    }

    @Test fun importsGooglePasswordManagerExport() {
        val csv = """
            name,url,username,password,note
            github.com,https://github.com/login,alice,"p,w""1",my note
            Instagram,android://$certB64@com.instagram.android/,insta_user,pw2,
            ,https://accounts.Example.co.ke/,bob,pw3,
            empty,https://x.com/,nobody,,
        """.trimIndent()
        val logins = PasswordCsv.import(csv)
        assertThat(logins).hasSize(3)
        assertThat(logins[0].webDomain).isEqualTo("github.com")
        assertThat(logins[0].password).isEqualTo("p,w\"1")
        assertThat(logins[0].notes).isEqualTo("my note")
        assertThat(logins[1].appPackage).isEqualTo("com.instagram.android")
        assertThat(logins[1].appCertSha256).isEqualTo(cert)
        assertThat(logins[1].webDomain).isNull()
        assertThat(logins[2].title).isEqualTo("accounts.example.co.ke")
    }

    @Test fun importsFirefoxExport() {
        val csv = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\"\n" +
            "\"https://example.org\",\"carol\",\"pw\",,\"https://example.org\",\"{x}\",\"1\"\n"
        val l = PasswordCsv.import(csv).single()
        assertThat(l.username).isEqualTo("carol")
        assertThat(l.webDomain).isEqualTo("example.org")
        assertThat(l.title).isEqualTo("example.org")
    }

    @Test fun rejectsUnknownFormat() {
        assertThrows(CsvFormatException::class.java) { PasswordCsv.import("a,b,c\n1,2,3") }
    }

    @Test fun exportRoundTripsThroughImport() {
        val entries = listOf(
            BackupEntry(id = "1", title = "Git, Hub", username = "al\"ice", password = "pw\nx", webDomain = "github.com", createdAt = 0, updatedAt = 0),
            BackupEntry(id = "2", title = "Insta", username = "u", password = "p", appPackage = "com.instagram.android", appCertSha256 = cert, createdAt = 0, updatedAt = 0),
        )
        val back = PasswordCsv.import(PasswordCsv.export(entries))
        assertThat(back.map { it.title }).containsExactly("Git, Hub", "Insta").inOrder()
        assertThat(back[0].username).isEqualTo("al\"ice")
        assertThat(back[0].password).isEqualTo("pw\nx")
        assertThat(back[1].appCertSha256).isEqualTo(cert)
    }
}
