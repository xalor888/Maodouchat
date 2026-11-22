package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ServerUrlPolicy] (checklist line 931, 37th block).
 * Test-only, zero production changes.
 *
 * The `validate` oracle re-implements `java.net.URI` parsing with string
 * operations only (no `java.net.URI` call, so the oracle is independent of
 * the production parser). Its corner behaviour was pinned against the real
 * JDK 17 parser with a probe program, not guessed: a non-numeric port on a
 * reg-name host parses fine but yields a null host (`INVALID`); the same on
 * an IPv6 literal fails with a port reason (`PORT`); an oversized numeric
 * port (> `Int.MAX_VALUE`) yields a null host; `_` or `%` in a reg-name host
 * yields a null host; a double dot or a leading dot yields a null host; a
 * space fails the whole parse with an authority/path/query/fragment reason;
 * a malformed `%` escape fails with "Malformed escape pair"; an IPv6
 * literal keeps its brackets in `URI.host` (stripped later by
 * `removeSurrounding`). A Python mini-model differentially checks the
 * oracle against an independent transcription of the production logic on
 * 5000 random URLs (0 diffs) before this file was written.
 */
class ServerUrlPolicyFuzzTest {

    private data class MiniUri(
        val scheme: String?,
        val host: String?,
        val rawAuthority: String?,
        val userInfo: String?,
        val port: Int,
        val rawPath: String?,
        val query: String?,
        val fragment: String?
    )

    private sealed interface MiniParse {
        data class Ok(val parts: MiniUri) : MiniParse
        data class Fail(val reason: String) : MiniParse
    }

    private fun isValidIpv6(inner: String): Boolean {
        if (inner.isEmpty()) return false
        val firstDouble = inner.indexOf("::")
        if (firstDouble != -1 && inner.indexOf("::", firstDouble + 2) != -1) return false
        val groups: List<String>
        if (firstDouble == -1) {
            groups = inner.split(':')
            if (groups.size != 8) return false
        } else {
            val left = inner.substring(0, firstDouble).split(':').filter { it.isNotEmpty() }
            val right = inner.substring(firstDouble + 2).split(':').filter { it.isNotEmpty() }
            if (left.size + right.size > 7) return false
            groups = left + right
        }
        return groups.all { group ->
            group.length in 1..4 &&
                group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    /** A reg-name host, or null when `java.net.URI` would report a null host. */
    private fun regNameHost(s: String): String? {
        if (s.isEmpty()) return null
        if (':' in s) return null
        if ('%' in s) return null
        if ('_' in s) return null
        if (s.any { it.code > 0x7F }) return null
        val labels = s.split('.')
        for ((index, label) in labels.withIndex()) {
            if (label.isEmpty() && !(index == labels.lastIndex && labels.size > 1)) return null
        }
        // A host whose last non-empty label starts with a digit is treated by
        // java.net.URI as an IPv4 candidate: it must be one all-digit label,
        // or exactly four all-digit labels each 0..255; anything else yields
        // a null host (probed: "127.1", "1a.2.3.4", "12.34.56.78x" are null,
        // "1.2.3.a" and "123abc.com" stay reg-names).
        val lastNonEmpty = labels.lastOrNull { it.isNotEmpty() }
        if (lastNonEmpty != null && lastNonEmpty.first().isDigit()) {
            if (labels.size == 1 && labels[0].all { it.isDigit() }) return s
            if (labels.size == 4 &&
                labels.all { it.all { c -> c.isDigit() } && it.toIntOrNull() in 0..255 }
            ) return s
            return null
        }
        return s
    }

    private val authorityIllegal = setOf(' ', '"', '<', '>', '\\', '^', '`', '{', '|', '}')

    private fun miniParseUri(raw: String): MiniParse {
        val firstDelim = raw.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it == -1) raw.length else it }
        val colon = raw.indexOf(':')
        var scheme: String? = null
        var rest = raw
        if (colon != -1 && colon < firstDelim) {
            val candidate = raw.substring(0, colon)
            if (!candidate.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*"))) {
                return MiniParse.Fail("Illegal character in scheme name")
            }
            scheme = candidate
            rest = raw.substring(colon + 1)
        }
        var rawAuthority: String? = null
        var remainder = rest
        if (scheme != null) {
            if (rest.startsWith("//")) {
                val afterSlashes = rest.substring(2)
                val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
                    .let { if (it == -1) afterSlashes.length else it }
                rawAuthority = afterSlashes.substring(0, end)
                remainder = afterSlashes.substring(end)
            } else {
                // Opaque URI: no authority and no path; validate only needs host == null.
                return MiniParse.Ok(MiniUri(scheme, null, null, null, -1, null, null, null))
            }
        } else if (rest.startsWith("//")) {
            val afterSlashes = rest.substring(2)
            val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it == -1) afterSlashes.length else it }
            rawAuthority = afterSlashes.substring(0, end)
            remainder = afterSlashes.substring(end)
        } else {
            // No scheme and no authority: validate reports SCHEME before anything else.
            return MiniParse.Ok(MiniUri(null, null, null, null, -1, null, null, null))
        }
        val authority = rawAuthority!!
        if (authority.any { it in authorityIllegal || it.code < 0x20 }) {
            return MiniParse.Fail("Illegal character in authority")
        }
        var userInfo: String? = null
        var hostport = authority
        if ('@' in authority) {
            userInfo = authority.substringBeforeLast('@')
            hostport = authority.substringAfterLast('@')
        }
        var host: String? = null
        var port = -1
        if (hostport.startsWith("[")) {
            val close = hostport.indexOf(']')
            if (close == -1) return MiniParse.Fail("Expected closing bracket for IPv6 address")
            val inner = hostport.substring(1, close)
            if (!isValidIpv6(inner)) return MiniParse.Fail("Malformed IPv6 address")
            val after = hostport.substring(close + 1)
            if (after.isEmpty()) {
                host = "[$inner]"
            } else if (after.startsWith(":")) {
                val portPart = after.substring(1)
                if (portPart.isEmpty()) {
                    host = "[$inner]"
                } else if (portPart.all { it.isDigit() }) {
                    val value = portPart.toLongOrNull()
                        ?: return MiniParse.Fail("Malformed port number")
                    if (value > Int.MAX_VALUE) return MiniParse.Fail("Malformed port number")
                    host = "[$inner]"
                    port = value.toInt()
                } else {
                    return MiniParse.Fail("Illegal character in port number")
                }
            } else {
                return MiniParse.Fail("Expected port number")
            }
        } else {
            val colonIdx = hostport.lastIndexOf(':')
            if (colonIdx == -1) {
                host = regNameHost(hostport)
            } else {
                val hostPart = hostport.substring(0, colonIdx)
                val portPart = hostport.substring(colonIdx + 1)
                if (portPart.isEmpty()) {
                    host = regNameHost(hostPart)
                } else if (portPart.all { it.isDigit() }) {
                    val value = portPart.toLongOrNull()
                    if (value == null || value > Int.MAX_VALUE) {
                        host = null
                    } else {
                        host = regNameHost(hostPart)
                        port = value.toInt()
                    }
                } else {
                    host = null
                }
            }
        }
        val fragIdx = remainder.indexOf('#')
        val queryIdx = remainder.indexOf('?')
        val pathEnd = when {
            queryIdx != -1 && (fragIdx == -1 || queryIdx < fragIdx) -> queryIdx
            fragIdx != -1 -> fragIdx
            else -> remainder.length
        }
        val rawPath = remainder.substring(0, pathEnd)
        val query = if (queryIdx != -1 && (fragIdx == -1 || queryIdx < fragIdx)) {
            remainder.substring(queryIdx + 1, if (fragIdx == -1) remainder.length else fragIdx)
        } else {
            null
        }
        val fragment = if (fragIdx != -1) remainder.substring(fragIdx + 1) else null
        for ((text, component) in listOf(rawPath to "path", query to "query", fragment to "fragment")) {
            if (text == null) continue
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return MiniParse.Fail("Malformed escape pair")
                    if (text[i + 1].digitToIntOrNull(16) == null ||
                        text[i + 2].digitToIntOrNull(16) == null
                    ) {
                        return MiniParse.Fail("Malformed escape pair")
                    }
                    i += 3
                } else {
                    if (c in authorityIllegal || c.code < 0x20) {
                        return MiniParse.Fail("Illegal character in $component")
                    }
                    i++
                }
            }
        }
        return MiniParse.Ok(MiniUri(scheme, host, rawAuthority, userInfo, port, rawPath, query, fragment))
    }

    private fun oracleIsLocalOrPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".local", ignoreCase = true)
        ) return true
        if (host == "127.0.0.1" || host == "::1") return true
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val p0 = parts[0].toInt()
            val p1 = parts[1].toInt()
            if (p0 == 10) return true
            if (p0 == 172 && p1 in 16..31) return true
            if (p0 == 192 && p1 == 168) return true
        }
        return false
    }

    private fun oracleHasUnsupportedPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.trim()
        return normalized != "/"
    }

    private fun oracleValidate(value: String, isDebug: Boolean = true): Problem? {
        val raw = value.trim()
        if (raw.isBlank()) return Problem.EMPTY
        val parts = when (val parsed = miniParseUri(raw)) {
            is MiniParse.Fail -> return when {
                parsed.reason.contains("port", ignoreCase = true) -> Problem.PORT
                parsed.reason.contains("authority", ignoreCase = true) ||
                    parsed.reason.contains("host", ignoreCase = true) ||
                    raw.endsWith(":") -> Problem.HOST
                else -> Problem.INVALID
            }
            is MiniParse.Ok -> parsed.parts
        }
        val scheme = parts.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Problem.SCHEME
        val host = parts.host?.trim()?.removeSurrounding("[", "]")?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() }
            ?: return if (parts.rawAuthority?.contains(":") == true) Problem.INVALID else Problem.HOST
        if (host.length > 255) return Problem.INVALID
        if (scheme == "http" && !isDebug && !oracleIsLocalOrPrivateHost(host)) return Problem.SCHEME
        if (parts.userInfo != null || parts.query != null || parts.fragment != null ||
            oracleHasUnsupportedPath(parts.rawPath)
        ) {
            return Problem.EXTRA
        }
        val port = parts.port
        if (port != -1 && port !in 1..65_535) return Problem.PORT
        return null
    }

    private val schemePool = listOf("http", "https", "HTTP", "Https", "ftp", "ws", "httpx", "1http", "ht tp")
    private val authorityPool = listOf(
        "chat.example.com", "example.com", "localhost", "LOCALHOST", "127.0.0.1",
        "10.0.0.1", "192.168.1.10", "172.16.0.1", "172.32.0.1", "8.8.8.8",
        "[::1]", "[2001:db8::1]", "[fe80::1]", "[::]", "[v6]", "[1::2::3]",
        "[gggg::1]", "chat.example.com.", "chat.example.com..", ".chat.example.com",
        "exa mple.com", "%41.com", "my_host.com",
        "a".repeat(251) + ".com", "a".repeat(252) + ".com",
        "chat.example.com:8080", "chat.example.com:0", "chat.example.com:65535",
        "chat.example.com:65536", "chat.example.com:99999", "chat.example.com:080",
        "chat.example.com:abc", "chat.example.com:not-a-port", "chat.example.com:12a",
        "chat.example.com:", "chat.example.com:2147483647", "chat.example.com:3000000000",
        "chat.example.com:+8080", "chat.example.com:-1", "chat.example.com:80x",
        "user@chat.example.com", "user:pass@chat.example.com", "@chat.example.com",
        "user@", ":8443", "", "[::1]:8443", "[::1]:abc", "[::1]:",
        "[::1]:3000000000", "[::1]:80x", "10.0.0.256", "999.0.0.1",
        "printer.local", "Printer.LOCAL", "localhost.evil.com", "127.0.0.2",
        "0.0.0.0", "2130706433", "127.1", "010.0.0.1", "chat.example.com: 8080"
    )
    private val pathPool = listOf(
        "", "/", "/api", "//", "/a b", "/%zz", "/%2", "/%", "/%41",
        "/a%2Fb", "/api/", "/?", "/a/b", "/中文", "/a+b", "/%41%42"
    )
    private val queryPool = listOf<String?>(null, "", "x=1", "q=%20", "q=%zz", "a=b&c=d")
    private val fragmentPool = listOf<String?>(null, "", "frag", "frag?x")

    private fun randomUrl(rnd: Random): Pair<String, Boolean> {
        val scheme = schemePool.random(rnd)
        val authority = authorityPool.random(rnd)
        val path = pathPool.random(rnd)
        val query = queryPool.random(rnd)
        val fragment = fragmentPool.random(rnd)
        val suffix = (query?.let { "?$it" } ?: "") + (fragment?.let { "#$it" } ?: "")
        val url = when (rnd.nextInt(10)) {
            0 -> "$scheme://$authority$path$suffix"
            1 -> "//$authority$path$suffix"
            2 -> "$scheme:$authority"
            3 -> scheme
            4 -> ""
            5 -> "   "
            else -> "$scheme://$authority$path$suffix"
        }
        val padded = when (rnd.nextInt(6)) {
            0 -> "  $url  "
            1 -> "\t$url\n"
            else -> url
        }
        return padded to rnd.nextBoolean()
    }

    private fun randomHost(rnd: Random): String {
        val parts = listOf(
            "-1", "0", "1", "9", "10", "16", "17", "31", "32", "168", "192",
            "193", "255", "256", "999", "010", "001", "+10", " 10", "10 ",
            "", "a", "1.2", "0x10"
        )
        return when (rnd.nextInt(8)) {
            0 -> listOf(
                "localhost", "LOCALHOST", "LocalHost", "localhost.evil.com",
                "foo.localhost", "local", "printer.local", "Printer.LOCAL",
                "example.local.evil.com", "a.local", ".local", "local."
            ).random(rnd)
            1 -> listOf(
                "127.0.0.1", "127.0.0.2", "127.1", "::1", "[::1]",
                "0.0.0.0", "2130706433"
            ).random(rnd)
            2 -> List(4) { parts.random(rnd) }.joinToString(".")
            3 -> List(rnd.nextInt(1, 6)) { parts.random(rnd) }.joinToString(".")
            4 -> "10.0.0.1"
            5 -> "192.168.1.10"
            6 -> listOf(
                "8.8.8.8", "1.1.1.1", "chat.example.com", "not-an-ip", "",
                " ", "10.0.0", "10.0.0.1.2"
            ).random(rnd)
            else -> List(4) { rnd.nextInt(-5, 300).toString() }.joinToString(".")
        }
    }

    private fun randomPath(rnd: Random): String? {
        val pool = listOf<String?>(
            null, "", " ", "/", "  ", "/ ", " /api ", "/api", "//", "chat",
            "/a/b", "/?", "/api/", "/%41", " / "
        )
        return pool.random(rnd)
    }

    @Test
    fun validateRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val (url, isDebug) = randomUrl(rnd)
            assertEquals(
                oracleValidate(url, isDebug),
                ServerUrlPolicy.validate(url, isDebug),
                "url=<$url> isDebug=<$isDebug>"
            )
        }
    }

    @Test
    fun localOrPrivateHostRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val host = randomHost(rnd)
            assertEquals(
                oracleIsLocalOrPrivateHost(host),
                ServerUrlPolicy.isLocalOrPrivateHost(host),
                "host=<$host>"
            )
        }
    }

    @Test
    fun unsupportedPathRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val path = randomPath(rnd)
            assertEquals(
                oracleHasUnsupportedPath(path),
                ServerUrlPolicy.hasUnsupportedPath(path),
                "path=<$path>"
            )
        }
    }

    @Test
    fun pinnedEdges() {
        // Port quirks: reg-name non-numeric port -> INVALID (null host);
        // IPv6 non-numeric / oversized port -> PORT (parse failure mentions port).
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com:not-a-port"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com:12a"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com:3000000000"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://chat.example.com:2147483647"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://[::1]:abc"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://[::1]:3000000000"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://[::1]:80x"))
        // Empty port is fine; oversized-but-fitting ports are PORT.
        assertNull(ServerUrlPolicy.validate("https://chat.example.com:"))
        assertEquals(Problem.PORT, ServerUrlPolicy.validate("https://chat.example.com:65536"))
        // Authority / scheme / path parse failures.
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://exa mple.com"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com/a b"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("1http://chat.example.com"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://[v6]"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://[1::2::3]"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://[gggg::1]"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com/%"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com/?q=%zz"))
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://chat.example.com/?q=a b"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://中文.com"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://chat.example.com.."))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://.chat.example.com"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://%41.com"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("https://my_host.com"))
        // IPv6 literals: brackets are stripped by the policy, empty port is fine.
        assertNull(ServerUrlPolicy.validate("https://[::1]"))
        assertNull(ServerUrlPolicy.validate("https://[::1]:"))
        assertNull(ServerUrlPolicy.validate("https://[::1]:8443"))
        assertNull(ServerUrlPolicy.validate("https://[::]"))
        // Userinfo / opaque / release-HTTP gates.
        assertEquals(Problem.INVALID, ServerUrlPolicy.validate("https://user:pass@"))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://@chat.example.com"))
        assertEquals(Problem.HOST, ServerUrlPolicy.validate("http:chat.example.com"))
        assertEquals(Problem.SCHEME, ServerUrlPolicy.validate("http://example.com:8080", isDebug = false))
        assertNull(ServerUrlPolicy.validate("http://10.0.0.5:8080", isDebug = false))
        assertNull(ServerUrlPolicy.validate("http://[::1]:8080", isDebug = false))
        assertEquals(Problem.EXTRA, ServerUrlPolicy.validate("https://chat.example.com:65535?x=1#f"))
        // Direct helpers: bracketed loopback is not local until brackets are stripped.
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost("[::1]"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("::1"))
        assertTrue(ServerUrlPolicy.isLocalOrPrivateHost("+10.0.0.1"))
        assertFalse(ServerUrlPolicy.isLocalOrPrivateHost(" 10.0.0.1"))
        assertFalse(ServerUrlPolicy.hasUnsupportedPath("/ "))
        assertTrue(ServerUrlPolicy.hasUnsupportedPath(" /api "))
    }
}
