package com.ares.vault.core.backup

import com.ares.vault.core.domain.Domains
import java.util.Base64

/** Minimal RFC 4180 CSV (quotes, doubled quotes, embedded newlines, CRLF, BOM). Not crypto. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = if (text.startsWith('﻿')) 1 else 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(field.toString()); field.setLength(0) }
                    '\r' -> Unit
                    '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = mutableListOf() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filterNot { r -> r.all { it.isBlank() } }
    }

    fun write(rows: List<List<String>>): String = buildString {
        rows.forEach { r -> append(r.joinToString(",") { quote(it) }).append("\r\n") }
    }

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || v.startsWith(' ') || v.endsWith(' ')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
}

/** A login read from another password manager's CSV export. */
data class ImportedLogin(
    val title: String,
    val username: String,
    val password: String,
    val notes: String,
    val webDomain: String?,
    val appPackage: String?,
    val appCertSha256: String?,
    val extraUrls: List<String> = emptyList(),
)

class CsvFormatException(message: String) : Exception(message)

/**
 * Google Password Manager CSV (`name,url,username,password,note`), also accepting Firefox's
 * (`url,username,password,…`) by matching column names. Android-app rows use Google's
 * `android://<base64url SHA-256 of signing cert>@<package>/` URLs, which carry both halves of
 * the app identity AresVault needs.
 */
object PasswordCsv {
    private val HEADER = listOf("name", "url", "username", "password", "note")

    fun import(text: String): List<ImportedLogin> {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val urlCol = col("url", "website", "login_uri") ?: throw CsvFormatException("No url column")
        val passCol = col("password", "login_password") ?: throw CsvFormatException("No password column")
        val userCol = col("username", "login_username", "email")
        val nameCol = col("name", "title")
        val noteCol = col("note", "notes", "extra")
        return rows.drop(1).mapNotNull { r ->
            fun get(c: Int?) = c?.let { r.getOrNull(it) }.orEmpty()
            val password = get(passCol)
            if (password.isEmpty()) return@mapNotNull null
            val url = get(urlCol).trim()
            val app = parseAndroidUrl(url)
            val host = if (app == null) Domains.normalizeHost(url) else null
            ImportedLogin(
                title = get(nameCol).trim().ifEmpty { app?.first ?: host ?: url.ifEmpty { "Imported" } },
                username = get(userCol).trim(),
                password = password,
                notes = get(noteCol),
                webDomain = host,
                appPackage = app?.first,
                appCertSha256 = app?.second,
            )
        }
    }

    /** Plaintext export in Google's format (re-importable by Google, Chrome and AresVault). */
    fun export(entries: List<BackupEntry>): String = Csv.write(
        listOf(HEADER) + entries.map { e ->
            val url = when {
                e.appPackage != null && e.appCertSha256 != null && ',' !in e.appCertSha256 ->
                    "android://${hexToBase64Url(e.appCertSha256)}@${e.appPackage}/"
                e.webDomain != null -> "https://${e.webDomain}/"
                else -> ""
            }
            listOf(e.title, url, e.username, e.password, e.notes)
        },
    )

    /** `android://<b64url cert>@<package>/` → (package, hex cert) or null. */
    fun parseAndroidUrl(url: String): Pair<String, String?>? {
        if (!url.startsWith("android://")) return null
        val rest = url.removePrefix("android://").trimEnd('/')
        val at = rest.lastIndexOf('@')
        val pkg = if (at >= 0) rest.substring(at + 1) else rest
        if (pkg.isEmpty() || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) return null
        val cert = if (at > 0) base64UrlToHex(rest.substring(0, at)) else null
        return pkg to cert
    }

    private fun base64UrlToHex(s: String): String? = try {
        Base64.getUrlDecoder().decode(s.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
            .takeIf { it.size == 32 }
            ?.joinToString("") { "%02x".format(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun hexToBase64Url(hex: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}
