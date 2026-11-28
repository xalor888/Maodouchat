package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AppUpdatePolicy] (checklist line 931, 35th block).
 * Test-only, zero production changes.
 *
 * Oracles are independent transcriptions of the production rules:
 * - [oracleHostOf] finds the host with an index scan for the first of '/', '?', ':'
 *   after "://", instead of the production substringBefore chain.
 * - Official hosts are the exact set {chat.mdou.me, mdou.me} plus the ".mdou.me"
 *   suffix, case-insensitive; anything else needs an exact runtime-host match.
 * - SHA-256 is a 64 hex-digit check on the trimmed string.
 * - [oracleFormatNotes] re-implements Java URLDecoder.decode ('+' -> ' ',
 *   consecutive %XX decoded as one lenient UTF-8 run, illegal % -> original;
 *   note Integer.parseInt accepts a leading sign, so "%+1" is byte 1 and
 *   "%-0" is byte 0 while "%-1" throws)
 *   and the Latin-1 mojibake recovery (ISO-8859-1 bytes -> UTF-8, U+FFFD -> original).
 * A Python mini-model differentially checks all five oracles on 5000 random
 * inputs each (0 diffs) before this file was written.
 */
class AppUpdatePolicyFuzzTest {

    private val schemes = listOf("https://", "HTTPS://", "Https://", "http://", "ftp://", "", "https:/", "https:// ")
    private val hosts = listOf(
        "chat.mdou.me", "mdou.me", "files.mdou.me", "deep.sub.mdou.me",
        "CHAT.MDOU.ME", "Mdou.Me", "CHAT.mdou.me",
        "mdou.me.evil.com", "notmdou.me", "fakemdou.me", "chat.mdou.me.evil.com",
        "evil.com", "github.com", "chat.mdou.me@evil.com", "mdou.me.", "x.mdou.me",
        "myserver.example", "other.example", "a.b.example.com"
    )
    private val ports = listOf("", "", "", ":8443", ":80", ":443", ":abc")
    private val paths = listOf("", "/", "/a.apk", "/a/b.apk?x=1", "?x=1", "///a.apk", "/a.apk#frag")
    private val hexUp = "0123456789ABCDEF"

    private fun oracleHostOf(url: String): String? {
        val idx = url.indexOf("://")
        if (idx < 0) return null
        val rest = url.substring(idx + 3)
        if (rest.isBlank()) return null
        var end = rest.length
        for (c in charArrayOf('/', '?', ':')) {
            val i = rest.indexOf(c)
            if (i >= 0 && i < end) end = i
        }
        return rest.substring(0, end).trim().ifBlank { null }
    }

    private fun oracleIsOfficial(apkUrl: String, runtimeBaseUrl: String?): Boolean {
        if (apkUrl.isBlank()) return false
        val trimmed = apkUrl.trim()
        if (!trimmed.lowercase().startsWith("https://")) return false
        val host = oracleHostOf(trimmed) ?: return false
        val lower = host.lowercase()
        if (lower == "chat.mdou.me" || lower == "mdou.me" || lower.endsWith(".mdou.me")) return true
        val runtimeHost = runtimeBaseUrl?.let { oracleHostOf(it) } ?: return false
        return host.equals(runtimeHost, ignoreCase = true)
    }

    private fun oracleHasSha(s: String): Boolean {
        val t = s.trim()
        if (t.length != 64) return false
        return t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun oracleMatchesSha(actual: String, expected: String): Boolean =
        oracleHasSha(expected) && actual.trim().equals(expected.trim(), ignoreCase = true)

    private fun oracleShouldOffer(
        current: Int,
        remote: Int,
        apkUrl: String,
        sha: String,
        runtimeBaseUrl: String?,
    ): Boolean {
        if (current < 0 || remote <= 0) return false
        if (!oracleIsOfficial(apkUrl, runtimeBaseUrl) || !oracleHasSha(sha)) return false
        return remote > current
    }

    private fun oracleFormatNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed.contains('%')) {
            val out = StringBuilder()
            val bytes = ArrayList<Byte>()
            fun flushBytes() {
                if (bytes.isEmpty()) return
                out.append(String(ByteArray(bytes.size) { bytes[it] }, Charsets.UTF_8))
                bytes.clear()
            }
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                when {
                    c == '+' -> {
                        flushBytes()
                        out.append(' ')
                        i++
                    }
                    c == '%' -> {
                        if (i + 2 >= trimmed.length) return trimmed
                        // Java URLDecoder parses the two chars with Integer.parseInt,
                        // which accepts a leading sign: "%+1" is byte 1, "%-0" is byte 0,
                        // "%-1" throws (negative value).
                        val w1 = trimmed[i + 1]
                        val w2 = trimmed[i + 2]
                        val byteValue = when {
                            w1 == '+' -> w2.digitToIntOrNull(16) ?: return trimmed
                            w1 == '-' -> if (w2 == '0') 0 else return trimmed
                            else -> {
                                val h1 = w1.digitToIntOrNull(16) ?: return trimmed
                                val h2 = w2.digitToIntOrNull(16) ?: return trimmed
                                (h1 shl 4) or h2
                            }
                        }
                        bytes.add(byteValue.toByte())
                        i += 3
                    }
                    else -> {
                        flushBytes()
                        out.append(c)
                        i++
                    }
                }
            }
            flushBytes()
            return out.toString()
        }
        if (trimmed.any { it.code in 0x80..0xFF }) {
            val bytes = trimmed.toByteArray(Charsets.ISO_8859_1)
            val utf8 = String(bytes, Charsets.UTF_8)
            return if (utf8.none { it == '\uFFFD' }) utf8 else trimmed
        }
        return trimmed
    }

    private fun randomUrl(rnd: Random): String {
        val sb = StringBuilder()
        if (rnd.nextInt(5) == 0) sb.append("  ")
        sb.append(schemes.random(rnd))
        sb.append(hosts.random(rnd))
        sb.append(ports.random(rnd))
        sb.append(paths.random(rnd))
        if (rnd.nextInt(5) == 0) sb.append("  ")
        if (rnd.nextInt(8) == 0) sb.append("\n")
        return sb.toString()
    }

    private fun randomSha(rnd: Random): String {
        return when (rnd.nextInt(10)) {
            0 -> ""
            1 -> "a".repeat(rnd.nextInt(0, 70))
            2 -> (1..64).map { "0123456789abcdefgxyz ".random(rnd) }.joinToString("")
            3 -> "  " + "a".repeat(64) + " "
            4 -> "A".repeat(64)
            else -> (1..64).map { "0123456789abcdefABCDEF".random(rnd) }.joinToString("")
        }
    }

    private fun randomRuntime(rnd: Random, apkHost: String?): String? {
        return when (rnd.nextInt(6)) {
            0 -> null
            1 -> "https://myserver.example"
            2 -> "https://myserver.example:8443/x"
            3 -> "myserver.example"
            4 -> if (apkHost != null) "https://$apkHost" else "https://other.example"
            else -> "https://other.example"
        }
    }

    private fun randomPercentString(rnd: Random): String {
        val sb = StringBuilder()
        repeat(rnd.nextInt(1, 6)) {
            when (rnd.nextInt(5)) {
                0 -> sb.append(('a'..'z').random(rnd))
                1 -> sb.append("%20")
                2 -> {
                    val ch = listOf(' ', '!', '~', 'é', '更', '新', '%').random(rnd)
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        val v = b.toInt() and 0xFF
                        sb.append('%').append(hexUp[v ushr 4]).append(hexUp[v and 0x0F])
                    }
                }
                3 -> {
                    // Java parseInt sign quirk: "%+<hex>" is a byte, "%-0" is byte 0.
                    sb.append('%').append(listOf('+', '-').random(rnd))
                        .append("0123456789abcdef".random(rnd))
                }
                else -> sb.append('+')
            }
        }
        return sb.toString()
    }

    private fun randomMojibakeString(rnd: Random): String {
        val sb = StringBuilder()
        repeat(rnd.nextInt(1, 12)) {
            when (rnd.nextInt(4)) {
                0 -> sb.append(('a'..'z').random(rnd))
                1 -> sb.append(rnd.nextInt(0x80, 0x100).toChar())
                2 -> sb.append(listOf('更', '新', '说', '明').random(rnd))
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    @Test
    fun hostOfRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val url = randomUrl(rnd)
            assertEquals(oracleHostOf(url), AppUpdatePolicy.hostOf(url), "url=<$url>")
        }
    }

    @Test
    fun isOfficialApkUrlRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val url = randomUrl(rnd)
            val runtime = randomRuntime(rnd, oracleHostOf(url))
            assertEquals(oracleIsOfficial(url, runtime), AppUpdatePolicy.isOfficialApkUrl(url, runtime), "url=<$url> runtime=<$runtime>")
        }
    }

    @Test
    fun shaRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val s = randomSha(rnd)
            assertEquals(oracleHasSha(s), AppUpdatePolicy.hasExpectedApkSha256(s), "sha=<$s>")
            val actual = randomSha(rnd)
            assertEquals(oracleMatchesSha(actual, s), AppUpdatePolicy.matchesExpectedApkSha256(actual, s), "actual=<$actual> expected=<$s>")
        }
    }

    @Test
    fun shouldOfferUpdateRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val current = listOf(rnd.nextInt(-5, 30), 0, Int.MIN_VALUE, Int.MAX_VALUE).random(rnd)
            val remote = listOf(rnd.nextInt(-5, 30), 0, 1, Int.MAX_VALUE).random(rnd)
            val url = randomUrl(rnd)
            val sha = randomSha(rnd)
            val runtime = randomRuntime(rnd, oracleHostOf(url))
            assertEquals(
                oracleShouldOffer(current, remote, url, sha, runtime),
                AppUpdatePolicy.shouldOfferUpdate(current, remote, url, sha, runtime),
                "current=$current remote=$remote url=<$url> sha=<$sha> runtime=<$runtime>"
            )
        }
    }

    @Test
    fun formatNotesPercentRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val s = randomPercentString(rnd)
            assertEquals(oracleFormatNotes(s), AppUpdatePolicy.formatNotes(s), "notes=<$s>")
        }
    }

    @Test
    fun formatNotesMojibakeRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val s = randomMojibakeString(rnd)
            assertEquals(oracleFormatNotes(s), AppUpdatePolicy.formatNotes(s), "notes=<$s>")
        }
    }

    @Test
    fun formatNotesMojibakeRoundTrip() {
        val rnd = Random(35)
        repeat(100) {
            val original = (1..rnd.nextInt(1, 8)).map { "更新说明测试版本修复".random(rnd) }.joinToString("")
            val mojibake = String(original.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
            assertEquals(original, AppUpdatePolicy.formatNotes(mojibake))
        }
    }

    @Test
    fun formatNotesAndOfficialHostPins() {
        assertEquals("", AppUpdatePolicy.formatNotes(""))
        assertEquals("", AppUpdatePolicy.formatNotes("   "))
        assertEquals("a+b", AppUpdatePolicy.formatNotes("a+b"))
        assertEquals("100%", AppUpdatePolicy.formatNotes("100%"))
        assertEquals("%2", AppUpdatePolicy.formatNotes("%2"))
        assertEquals("%", AppUpdatePolicy.formatNotes("%25"))
        assertEquals("更新", AppUpdatePolicy.formatNotes("%E6%9B%B4%E6%96%B0"))
        assertEquals("\uFFFD", AppUpdatePolicy.formatNotes("%FF"))
        assertEquals("\uFFFD", AppUpdatePolicy.formatNotes("%E6%9B"))
        assertEquals("\uFFFDB4", AppUpdatePolicy.formatNotes("%E6%9BB4"))
        assertEquals("\u0001", AppUpdatePolicy.formatNotes("%+1"))
        assertEquals("\u0000", AppUpdatePolicy.formatNotes("%-0"))
        assertEquals("%-1", AppUpdatePolicy.formatNotes("%-1"))
        assertEquals("%+G", AppUpdatePolicy.formatNotes("%+G"))
        assertEquals("a b c", AppUpdatePolicy.formatNotes("a+b%20c"))
        val mojibake = String("更新说明".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("更新说明", AppUpdatePolicy.formatNotes(mojibake))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me@evil.com/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://mdou.me.evil.com/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://fakemdou.me/a.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://deep.sub.mdou.me/a.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl(""))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://myserver.example:8443/a.apk", runtimeBaseUrl = "https://myserver.example/"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://myserver.example/a.apk", runtimeBaseUrl = "myserver.example"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://myserver.example/a.apk", runtimeBaseUrl = ""))
        assertTrue(AppUpdatePolicy.shouldOfferUpdate(0, 1, "https://chat.mdou.me/a.apk", "a".repeat(64)))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(-1, 2, "https://chat.mdou.me/a.apk", "a".repeat(64)))
    }
}
