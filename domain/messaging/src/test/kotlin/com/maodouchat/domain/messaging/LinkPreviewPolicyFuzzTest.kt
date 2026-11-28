package com.maodouchat.domain.messaging

import java.net.InetAddress
import java.net.Inet6Address
import java.net.URI
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [LinkPreviewPolicy] (checklist line 931, 45th block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements the SSRF policy independently: explicit scanners instead
 * of the production regexes (URL extraction, meta/title tags), explicit char scans
 * instead of the IPv4-literal regexes, IPv6 predicates from the 16 raw address
 * bytes, a hand-rolled URI host parser for displayHost, and explicit
 * whitespace/entity phases for cleanText. Shared JDK primitives (production
 * calls them too, so sharing is exact): java.net.URI for parsing/resolving,
 * InetAddress.getByName for IPv6 bytes, lowercase(ROOT) / trim() / isBlank().
 * The scanners fold case ASCII-only: Java regex (?i) folds ASCII only, while
 * Kotlin regionMatches folds Unicode, so equals(..., ignoreCase = true) would
 * diverge from the regex on chars like 'ſ'.
 *
 * Verification (offline, JDK 17): the real production code was compiled with
 * the Kotlin compiler and differentially checked against this oracle on 12,450
 * cases — the exact inputs these tests generate (Random(42)), a host/scheme/
 * port/path sweep, a meta-template sweep, and a 5,000-case adversarial corpus
 * — with 0 differences; the 143 pinned expectations below were each checked
 * against real production, as were the invariants in sanitizeInvariantsRandom.
 * Two quirks are pinned, not fixed: firstHttpUrl takes the first regex match
 * only (a SSRF-rejected first URL yields null even when a later URL is fine),
 * and java.net.URI.resolve does not do RFC 3986 dot-segment removal
 * ("/a/b/a/./b/../c.png" resolves to "/a/a/c.png").
 */
class LinkPreviewPolicyFuzzTest {

    // ---------- small ASCII helpers ----------

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAsciiAlnum(c: Char): Boolean = isAsciiLetter(c) || isAsciiDigit(c)
    private fun isWordChar(c: Char): Boolean = isAsciiAlnum(c) || c == '_'
    private fun isAsciiWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
    private fun isHexDigit(c: Char): Boolean = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'
    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') (c.code + 32).toChar() else c
    private fun asciiEq(a: Char, b: Char): Boolean = asciiLower(a) == asciiLower(b)

    /** ASCII case-insensitive literal match of [lit] at [from] in [s]. */
    private fun asciiEqStr(s: String, from: Int, lit: String): Boolean {
        if (from < 0 || from + lit.length > s.length) return false
        for (i in lit.indices) if (!asciiEq(s[from + i], lit[i])) return false
        return true
    }

    /** Next ASCII case-insensitive occurrence of [lit] at or after [from], before [limit], or -1. */
    private fun indexOfAsciiCi(s: String, lit: String, from: Int, limit: Int): Int {
        var i = from.coerceAtLeast(0)
        val lastStart = (limit - lit.length).coerceAtMost(s.length - lit.length)
        while (i <= lastStart) {
            if (asciiEqStr(s, i, lit)) return i
            i++
        }
        return -1
    }

    // ---------- sanitizeUrl oracle ----------

    private val ALLOWED_WEB_PORTS = setOf(80, 443, 8080, 8443)

    /** Single-label integer/hex literal: ^(0x[0-9a-fA-F]+|\d+)$ — "0x" lowercase only. */
    private fun isSingleIntOrHex(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.startsWith("0x")) {
            if (s.length == 2) return false
            return s.substring(2).all { isHexDigit(it) }
        }
        return s.all { isAsciiDigit(it) }
    }

    /** One dotted label for the numeric-labels path: (?i)^(0x[0-9a-fA-F]+|\d+)$ */
    private fun isHexOrDecLabel(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.length >= 2 && asciiEq(s[0], '0') && asciiEq(s[1], 'x')) {
            if (s.length == 2) return false
            return s.substring(2).all { isHexDigit(it) }
        }
        return s.all { isAsciiDigit(it) }
    }

    private fun sanitizeUrlOracle(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        val host = uri.host?.trim()?.trim('.') ?: return null
        if (host.isBlank()) return null
        if (host.contains("%")) return null
        val port = uri.port
        if (port != -1 && port !in ALLOWED_WEB_PORTS) return null
        if (host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".local", ignoreCase = true) ||
            host.endsWith(".internal", ignoreCase = true)
        ) {
            return null
        }
        if (isSingleIntOrHex(host)) return null
        val parts = host.split(".")
        if (parts.all { isHexOrDecLabel(it) }) {
            if (parts.size != 4) return null
            if (parts.any { it.length >= 2 && asciiEq(it[0], '0') && asciiEq(it[1], 'x') }) return null
            val octets = parts.map { it.toIntOrNull() }
            if (parts.zip(octets).any { (raw, n) -> n == null || n !in 0..255 || raw != n.toString() }) return null
            val a = octets[0]!!
            val b = octets[1]!!
            val c = octets[2]!!
            if (a == 0) return null
            if (a == 10) return null
            if (a == 127) return null
            if (a == 169 && b == 254) return null
            if (a == 172 && b in 16..31) return null
            if (a == 192 && b == 168) return null
            if (a == 100 && b in 64..127) return null
            if (a == 192 && b == 0 && c == 0) return null
            if (a == 192 && b == 0 && c == 2) return null
            if (a == 198 && b in 18..19) return null
            if (a == 198 && b == 51 && c == 100) return null
            if (a == 203 && b == 0 && c == 113) return null
            if (a >= 224) return null
        }
        if (host.contains(":")) {
            val h = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
            if (h.contains("::ffff:") || isNonPublicIpv6Oracle(h)) return null
        }
        return uri.toString()
    }

    /** IPv6 non-public predicates reimplemented from the 16 raw bytes. */
    private fun isNonPublicIpv6Oracle(host: String): Boolean {
        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return true
        }
        if (address !is Inet6Address) return true
        val bytes = address.address
        if (bytes.size != 16) return true
        val b = IntArray(16) { bytes[it].toInt() and 0xff }
        // :: (unspecified)
        if (b.all { it == 0 }) return true
        // ::1 (loopback)
        if (b.slice(0..14).all { it == 0 } && b[15] == 1) return true
        // fe80::/10 (link-local)
        if (b[0] == 0xfe && (b[1] and 0xc0) == 0x80) return true
        // fec0::/10 (site-local, deprecated)
        if (b[0] == 0xfe && (b[1] and 0xc0) == 0xc0) return true
        // ff00::/8 (multicast)
        if (b[0] == 0xff) return true
        // ::/96 with a real IPv4 tail (IPv4-compatible, excluding :: and ::1 themselves)
        val first12Zero = b.slice(0..11).all { it == 0 }
        val tailIsZero = b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] == 0
        val tailIsOne = b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] == 1
        if (first12Zero && !tailIsZero && !tailIsOne) return true
        // fc00::/7 (ULA)
        if ((b[0] and 0xfe) == 0xfc) return true
        // 2001:db8::/32 (documentation)
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return true
        return false
    }

    // ---------- firstHttpUrl oracle (explicit scanner, not the regex) ----------

    private val URL_CHARS: Set<Char> = (
        ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(
            '_', '-', '.', '~', ':', '/', '?', '#', '[', ']', '@', '!', '$', '&',
            '\'', '(', ')', '*', '+', ',', ';', '=', '%'
        )
    ).toSet()

    private fun firstHttpUrlOracle(text: String): String? {
        val idx = text.lastIndexOf("<meta>")
        val plain = (if (idx < 0) text else text.substring(0, idx)).trim()
        if (plain.isBlank()) return null
        var i = 0
        var raw: String? = null
        while (i < plain.length) {
            val boundary = i == 0 || !isWordChar(plain[i - 1])
            if (boundary) {
                val litLen = when {
                    asciiEqStr(plain, i, "https://") -> 8
                    asciiEqStr(plain, i, "http://") -> 7
                    asciiEqStr(plain, i, "www.") -> 4
                    else -> 0
                }
                if (litLen > 0) {
                    var j = i + litLen
                    while (j < plain.length && plain[j] in URL_CHARS) j++
                    if (j > i + litLen) {
                        raw = plain.substring(i, j).trimEnd('.', ',', ')', ']', '}', '"', '\'')
                        break
                    }
                }
            }
            i++
        }
        val r = raw ?: return null
        if (r.isBlank()) return null
        val withScheme = when {
            r.startsWith("http://", ignoreCase = true) ||
                r.startsWith("https://", ignoreCase = true) -> r
            r.startsWith("www.", ignoreCase = true) -> "https://$r"
            else -> return null
        }
        return sanitizeUrlOracle(withScheme)
    }

    // ---------- displayHost oracle (hand-rolled host extraction) ----------

    private fun displayHostOracle(url: String): String {
        val host = extractHostOracle(url)
        return (host?.removePrefix("www.") ?: url).take(96)
    }

    private fun isSchemeOracle(s: String): Boolean {
        if (s.isEmpty() || !isAsciiLetter(s[0])) return false
        return s.all { isAsciiAlnum(it) || it == '+' || it == '-' || it == '.' }
    }

    private fun extractHostOracle(url: String): String? {
        val authStart: Int
        if (url.startsWith("//")) {
            authStart = 2
        } else {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            if (!isSchemeOracle(url.substring(0, colon))) return null
            if (colon + 2 >= url.length || url[colon + 1] != '/' || url[colon + 2] != '/') return null
            authStart = colon + 3
        }
        var authEnd = authStart
        while (authEnd < url.length && url[authEnd] != '/' && url[authEnd] != '?' && url[authEnd] != '#') authEnd++
        if (!isLegalRemainderOracle(url.substring(authEnd))) return null
        val authority = url.substring(authStart, authEnd)
        if (authority.isEmpty()) return null
        // userinfo is split at the FIRST '@' (java.net.URI behaviour: "h@h@h" -> host null).
        val atIdx = authority.indexOf('@')
        if (atIdx >= 0) {
            val userinfo = authority.substring(0, atIdx)
            if (!isLegalUserinfoOracle(userinfo)) return null
        }
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        if (hostPort.isEmpty()) return null
        return parseHostPortOracle(hostPort)
    }

    /** '%' must open a valid escape; ISO controls, Unicode space chars and " < > \\ ^ ` { | } are illegal in userinfo. */
    private fun isLegalUserinfoOracle(userinfo: String): Boolean {
        var i = 0
        while (i < userinfo.length) {
            val c = userinfo[i]
            if (c == '%') {
                if (i + 2 >= userinfo.length || !isHexDigit(userinfo[i + 1]) || !isHexDigit(userinfo[i + 2])) {
                    return false
                }
                i += 3
            } else {
                if (isIllegalUriCharOracle(c, allowBrackets = true)) return false
                i++
            }
        }
        return true
    }

    /**
     * Characters java.net.URI rejects anywhere (probed on JDK 17): ISO controls
     * (U+0000..U+001F, U+007F..U+009F), Unicode space chars (incl. NBSP, which
     * Character.isWhitespace would NOT reject), and " < > \\ ^ ` { | }. '[' and ']'
     * are additionally illegal in the path, but legal in query/fragment.
     */
    private fun isIllegalUriCharOracle(c: Char, allowBrackets: Boolean): Boolean {
        if (c.code in 0x00..0x1F || c.code in 0x7F..0x9F) return true
        if (Character.isSpaceChar(c)) return true
        if (c == '"' || c == '<' || c == '>' || c == '\\' || c == '^' || c == '`' ||
            c == '{' || c == '|' || c == '}'
        ) {
            return true
        }
        if (!allowBrackets && (c == '[' || c == ']')) return true
        return false
    }

    /** The path/query/fragment after the authority must be URI-legal, or the whole parse throws. */
    private fun isLegalRemainderOracle(remainder: String): Boolean {
        var i = 0
        var inQueryOrFragment = false
        while (i < remainder.length) {
            val c = remainder[i]
            if (c == '?' || c == '#') inQueryOrFragment = true
            if (c == '%') {
                if (i + 2 >= remainder.length || !isHexDigit(remainder[i + 1]) || !isHexDigit(remainder[i + 2])) {
                    return false
                }
                i += 3
            } else {
                if (isIllegalUriCharOracle(c, allowBrackets = inQueryOrFragment)) return false
                i++
            }
        }
        return true
    }

    private fun parseHostPortOracle(hostPort: String): String? {
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            if (!isValidIpv6LiteralOracle(hostPort.substring(1, close))) return null
            val rest = hostPort.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1)
                if (port.isNotEmpty() && !port.all { isAsciiDigit(it) }) return null
            }
            return hostPort.substring(0, close + 1)
        }
        val colon = hostPort.indexOf(':')
        val host = if (colon < 0) hostPort else hostPort.substring(0, colon)
        if (colon >= 0) {
            val port = hostPort.substring(colon + 1)
            if (port.isNotEmpty() && !port.all { isAsciiDigit(it) }) return null
        }
        if (host.isEmpty()) return null
        if (!isValidHostnameOracle(host)) return null
        return host
    }

    /**
     * Mirrors java.net.URI.getHost() hostname validation (probed on JDK 17):
     * - all-numeric multi-label hosts must be a valid IPv4 (exactly 4 octets 0..255);
     * - otherwise every label is [A-Za-z0-9] with interior '-', starts/ends alnum,
     *   no empty label except one trailing dot, and — with >= 2 labels — the last
     *   non-empty label must start with a letter ("a.1" null, "1.a" ok, "123" ok).
     */
    private fun isValidHostnameOracle(host: String): Boolean {
        if (host.isEmpty()) return false
        val labels = host.split(".")
        val nonEmpty = labels.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return false
        if (labels.dropLast(1).any { it.isEmpty() }) return false
        if (nonEmpty.size >= 2 && nonEmpty.all { label -> label.all { isAsciiDigit(it) } }) {
            if (labels.size != 4) return false
            return labels.all { label ->
                val n = label.toIntOrNull()
                n != null && n in 0..255
            }
        }
        for (label in nonEmpty) {
            if (!isAsciiAlnum(label[0]) || !isAsciiAlnum(label[label.length - 1])) return false
            if (!label.all { isAsciiAlnum(it) || it == '-' }) return false
        }
        if (nonEmpty.size >= 2 && !isAsciiLetter(nonEmpty.last()[0])) return false
        return true
    }

    // ---------- IPv6 literal validation (for the hand-rolled host parser) ----------

    private fun isHexGroup(s: String): Boolean = s.isNotEmpty() && s.length <= 4 && s.all { isHexDigit(it) }

    private fun isValidIpv4Oracle(v4: String): Boolean {
        val parts = v4.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { isAsciiDigit(it) } &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    private fun isValidIpv6GroupsOracle(s: String, totalGroups: Int): Boolean {
        if (s.isEmpty()) return false
        val doubleIdx = s.indexOf("::")
        if (doubleIdx >= 0 && s.indexOf("::", doubleIdx + 1) >= 0) return false
        if (doubleIdx < 0) {
            val groups = s.split(":")
            if (groups.size != totalGroups) return false
            return groups.all { isHexGroup(it) }
        }
        val left = s.substring(0, doubleIdx)
        val right = s.substring(doubleIdx + 2)
        val leftGroups = if (left.isEmpty()) emptyList() else left.split(":")
        val rightGroups = if (right.isEmpty()) emptyList() else right.split(":")
        if (leftGroups.any { !isHexGroup(it) }) return false
        if (rightGroups.any { !isHexGroup(it) }) return false
        // "::" must compress at least one group.
        return leftGroups.size + rightGroups.size < totalGroups
    }

    private fun isValidIpv6AddrOracle(addr: String): Boolean {
        if (addr.isEmpty()) return false
        if (!addr.contains('.')) return isValidIpv6GroupsOracle(addr, 8)
        val lastColon = addr.lastIndexOf(':')
        if (lastColon < 0) return false
        val v4 = addr.substring(lastColon + 1)
        if (!isValidIpv4Oracle(v4)) return false
        val normalized = addr.substring(0, lastColon + 1) + "0:0"
        return isValidIpv6GroupsOracle(normalized, 8)
    }

    /** Bracket content: IPv6 address plus an optional '%' zone suffix (URI is lenient there). */
    private fun isValidIpv6LiteralOracle(lit: String): Boolean {
        val pct = lit.indexOf('%')
        val addr = if (pct >= 0) lit.substring(0, pct) else lit
        return isValidIpv6AddrOracle(addr)
    }

    // ---------- parseHtmlPreview oracle (explicit scanners, not the regexes) ----------


    private fun parseHtmlPreviewOracle(url: String, html: String): Preview {
        val head = extractHeadOracle(html)
        val title = metaOracle(head, property = "og:title")
            ?: metaOracle(head, name = "twitter:title")
            ?: titleTagOracle(head)
        val description = metaOracle(head, property = "og:description")
            ?: metaOracle(head, name = "description")
            ?: metaOracle(head, name = "twitter:description")
        val image = metaOracle(head, property = "og:image")
            ?: metaOracle(head, name = "twitter:image")
        val site = metaOracle(head, property = "og:site_name")
            ?: displayHostOracle(url)
        return Preview(
            url = url,
            title = cleanTextOracle(title)?.take(220),
            description = cleanTextOracle(description)?.take(400),
            imageUrl = resolveUrlOracle(url, image),
            siteName = cleanTextOracle(site)?.take(120),
        )
    }

    private fun isUsefulOracle(preview: Preview): Boolean {
        return !preview.title.isNullOrBlank() ||
            !preview.description.isNullOrBlank() ||
            !preview.imageUrl.isNullOrBlank()
    }

    private fun extractHeadOracle(html: String): String {
        val lower = html.lowercase(Locale.ROOT)
        val end = lower.indexOf("</head>").let { if (it < 0) html.length.coerceAtMost(65_536) else it }
        return html.substring(0, end.coerceAtMost(html.length))
    }

    /**
     * Find `\bNAME\s*=\s*["']KEY["']` starting in [from, limit): the name must start
     * before the first '>' (limit) and carry a word boundary before it; returns the
     * index just after the closing quote, or -1. Mirrors the regex's backtracking:
     * every occurrence is tried in order.
     */
    private fun findAttrAllOracle(head: String, from: Int, limit: Int, name: String, key: String): List<Int> {
        val ends = mutableListOf<Int>()
        var p = from
        while (true) {
            p = indexOfAsciiCi(head, name, p, limit)
            if (p < 0) return ends
            if (p > 0 && isWordChar(head[p - 1])) {
                p++
                continue
            }
            var i = p + name.length
            while (i < limit && isAsciiWs(head[i])) i++
            if (i >= limit || head[i] != '=') {
                p++
                continue
            }
            i++
            while (i < limit && isAsciiWs(head[i])) i++
            if (i >= limit || (head[i] != '"' && head[i] != '\'')) {
                p++
                continue
            }
            if (!asciiEqStr(head, i + 1, key)) {
                p++
                continue
            }
            val afterKey = i + 1 + key.length
            if (afterKey >= limit || (head[afterKey] != '"' && head[afterKey] != '\'')) {
                p++
                continue
            }
            ends.add(afterKey + 1)
            p++
        }
    }

    /**
     * Find `\bcontent\s*=\s*["']([^"']+)["']` in [from, limit): returns the value
     * plus the index just after its closing quote, or null. The value itself may
     * contain '>' (the regex's [^"'] class allows it), so the end may lie past
     * the first '>' after [from].
     */
    private fun findContentAllOracle(head: String, from: Int, limit: Int): List<Pair<String, Int>> {
        val found = mutableListOf<Pair<String, Int>>()
        var p = from
        while (true) {
            p = indexOfAsciiCi(head, "content", p, limit)
            if (p < 0) return found
            if (p > 0 && isWordChar(head[p - 1])) {
                p++
                continue
            }
            var i = p + "content".length
            while (i < limit && isAsciiWs(head[i])) i++
            if (i >= limit || head[i] != '=') {
                p++
                continue
            }
            i++
            while (i < limit && isAsciiWs(head[i])) i++
            if (i >= limit || (head[i] != '"' && head[i] != '\'')) {
                p++
                continue
            }
            var j = i + 1
            while (j < head.length && head[j] != '"' && head[j] != '\'') j++
            if (j >= head.length) return found
            val value = head.substring(i + 1, j)
            if (value.isEmpty()) {
                p++
                continue
            }
            found.add(Pair(value, j + 1))
            p++
        }
    }

    /**
     * The two regex alternatives, in order: (attr then content) or (content then attr).
     * The tail only needs some '>' after the second part's closing quote.
     */
    private fun metaOracle(head: String, property: String? = null, name: String? = null): String? {
        val key = property ?: name ?: return null
        val attr = if (property != null) "property" else "name"
        var start = 0
        while (true) {
            val metaPos = indexOfAsciiCi(head, "<meta", start, head.length)
            if (metaPos < 0) return null
            // Regex has <meta\b: the char after "meta" must not be a word char.
            if (metaPos + 5 < head.length && isWordChar(head[metaPos + 5])) {
                start = metaPos + 1
                continue
            }
            val afterMeta = metaPos + 5
            val firstGt = head.indexOf('>', afterMeta).let { if (it < 0) head.length else it }
            // Alternative 1: attr, then content. Both [^>]* runs are greedy, so the
            // regex tries the LAST occurrence of each first, backtracking outward.
            val attrEnds = findAttrAllOracle(head, afterMeta, firstGt, attr, key)
            for (attrEnd in attrEnds.asReversed()) {
                val nextGt = head.indexOf('>', attrEnd).let { if (it < 0) head.length else it }
                for ((value, contentEnd) in findContentAllOracle(head, attrEnd, nextGt).asReversed()) {
                    if (head.indexOf('>', contentEnd) >= 0) return value
                }
            }
            // Alternative 2: content, then attr, same last-to-first backtracking.
            for ((value, contentEnd) in findContentAllOracle(head, afterMeta, firstGt).asReversed()) {
                val nextGt = head.indexOf('>', contentEnd).let { if (it < 0) head.length else it }
                for (attrEnd in findAttrAllOracle(head, contentEnd, nextGt, attr, key).asReversed()) {
                    if (head.indexOf('>', attrEnd) >= 0) return value
                }
            }
            start = metaPos + 1
        }
    }

    /** `(?is)<title[^>]*>(.*?)</title>` as a scanner (note: no \b after <title). */
    private fun titleTagOracle(head: String): String? {
        var start = 0
        while (true) {
            val p = indexOfAsciiCi(head, "<title", start, head.length)
            if (p < 0) return null
            val gt = head.indexOf('>', p + 6)
            if (gt < 0) {
                start = p + 1
                continue
            }
            val close = indexOfAsciiCi(head, "</title>", gt + 1, head.length)
            if (close < 0) {
                start = p + 1
                continue
            }
            return head.substring(gt + 1, close)
        }
    }

    private fun collapseWsOracle(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (isAsciiWs(s[i])) {
                sb.append(' ')
                while (i < s.length && isAsciiWs(s[i])) i++
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun replaceAllLiteral(s: String, from: String, to: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (true) {
            val j = s.indexOf(from, i)
            if (j < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, j)
            sb.append(to)
            i = j + from.length
        }
        return sb.toString()
    }

    private fun cleanTextOracle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = collapseWsOracle(raw)
        s = replaceAllLiteral(s, "&amp;", "&")
        s = replaceAllLiteral(s, "&lt;", "<")
        s = replaceAllLiteral(s, "&gt;", ">")
        s = replaceAllLiteral(s, "&quot;", "\"")
        s = replaceAllLiteral(s, "&#39;", "'")
        return s.trim().takeIf { it.isNotBlank() }
    }

    private fun resolveUrlOracle(base: String, maybeRelative: String?): String? {
        val raw = maybeRelative?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val resolved = URI(base).resolve(raw).toString()
            sanitizeUrlOracle(resolved)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- generators (kotlin.random.Random, fixed seeds per test) ----------

    private val SANITIZE_SCHEMES = listOf(
        "http", "https", "HTTP", "HtTp", "hTTpS", "ftp", "javascript", "", "http ",
        " http", "httpx", "h", "ws", "HTTPs", "HtTps", "http+foo", "a+b"
    )
    private val SANITIZE_USERINFOS = listOf(
        "", "", "", "", "user@", "user:pass@", "@", ":@", "user%40@", "%41@",
        "a b@", "user:pa:ss@", "us%zz@", "user%2f@"
    )
    private val SANITIZE_HOSTS = listOf(
        "example.com", "www.example.com", "sub.example.co.uk", "example.com.",
        ".example.com", "EXAMPLE.COM", "example.COM", "ex-ample.com", "a.b", "x",
        "localhost", "LOCALHOST", "LocalHost", "foo.local", "foo.LOCAL",
        "a.internal", "internal", "example.localhost", "localhost.local",
        "notlocalhost", "localhostx", "xlocalhost", "127.0.0.1", "127.1",
        "127.0.1", "10.0.0.1", "10.255.255.255", "192.168.0.1", "172.16.0.1",
        "172.31.255.255", "172.32.0.1", "169.254.169.254", "0.0.0.0", "0.0.0.1",
        "8.8.8.8", "1.1.1.1", "255.255.255.255", "224.0.0.1", "240.0.0.1",
        "100.64.0.1", "100.127.255.255", "100.128.0.1", "192.0.0.1", "192.0.2.1",
        "198.18.0.1", "198.19.0.1", "198.51.100.1", "203.0.113.1", "2130706433",
        "0x7f000001", "0X7F000001", "0177.0.0.1", "0x7f.0.0.1", "127.0.0.0x1",
        "0300.0250.0001.0001", "127.0.0.01", "127.0.0.256", "127.0.0.-1",
        "127.0.0.+1", "127.0.0.999999999999999999999", "1.2.3", "1.2.3.4.5",
        "1.2.3.4.", ".1.2.3.4", "1..2.3", "1.2.3.04", "01.2.3.4", "0", "00",
        "000", "0x0", "0x", "123456789", "4294967295", "4294967296", "3232235521",
        "3232235777", "192.168.1", "192.168.1.1.1", "9.9.9.9", "11.0.0.1",
        "100.63.0.1", "101.0.0.1", "172.15.0.1", "192.0.1.1", "192.0.3.1",
        "198.17.0.1", "198.20.0.1", "198.51.99.1", "198.51.101.1", "203.0.112.1",
        "203.0.114.1", "223.255.255.255", "a.1", "1.a", "a.1a", "a.a1", "123",
        "1.2.3.a", "0x7f000001", "[::1]", "[::]", "[fe80::1]", "[fec0::1]",
        "[ff02::1]", "[fc00::1]", "[fd00::1]", "[::ffff:127.0.0.1]",
        "[::ffff:7f00:1]", "[2001:db8::1]", "[2001:db8:85a3::8a2e:370:7334]",
        "[2606:4700:4700::1111]", "[::127.0.0.1]", "[::2]", "[1::]",
        "[1:2:3:4:5:6:7:8]", "[1:2:3:4:5:6:7:8:9]", "[::ffff:0:127.0.0.1]",
        "[fe80::1%eth0]", "[fe80::1%25eth0]", "[v6]", "[::1", "[]",
        "[::ffff:example.com]", "[0:0:0:0:0:ffff:127.0.0.1]",
        "[::ffff:7f00:0001]", "[1::2::3]", "[::ffff:999.1.1.1]",
        "exa_mple.com", "exam ple.com", "%41.com", "%65xample.com",
        "example%2ecom", "example.com%2f", "-example.com", "_example.com",
        "example..com", "example.com..", "..", ".", "-", "_", "%", "%25",
        "%zz", "foo%bar", "bücher.de", "exämple.com", "a_b.com", "a+b.com",
        "a;b.com", "xn--nxasmq6b.example", "1.2.3.400", "999.999.999.999",
        "a.1.2a", "01a.2b", "a.01", "a--b.com", "a-.com", "-a.com"
    )
    private val SANITIZE_PORTS = listOf(
        "", "", "", ":80", ":443", ":8080", ":8443", ":8000", ":22", ":0",
        ":65535", ":65536", ":99999", ":080", ":0443", ":abc", ":", ":-1",
        ":+80", ": 80", ":80x", ":80808"
    )
    private val SANITIZE_PATHS = listOf(
        "", "", "/", "/a", "/a b", "/a%20b", "/%zz", "/%2", "/?", "/#",
        "/a?b=c", "/a#f", "/a?b c", "/a|b", "/a\"b", "/a<b", "/a\\b", "/a`b",
        "/a{b", "/a^b", "/{}", "/a/./b", "/a/../b", "/%41", "/%c2%a0",
        "/a?b=c&d=e", "/a#frag", "/%2e%2e/%2e%2e/etc/passwd"
    )

    private fun composeSanitizeUrl(rnd: Random): String {
        val scheme = SANITIZE_SCHEMES.random(rnd)
        val userinfo = if (rnd.nextInt(100) < 80) "" else SANITIZE_USERINFOS.random(rnd)
        val host = SANITIZE_HOSTS.random(rnd)
        val port = if (rnd.nextInt(100) < 70) "" else SANITIZE_PORTS.random(rnd)
        val path = if (rnd.nextInt(100) < 60) "" else SANITIZE_PATHS.random(rnd)
        return "$scheme://$userinfo$host$port$path"
    }

    private val SOUP_ALPHABET = listOf(
        "h", "t", "p", "s", ":", "/", ".", "w", "a", "e", "x", "m", "1", "2",
        "0", "%", "@", "[", "]", "?", "#", "&", "=", "-", "_", " ", "ü", "ß"
    )

    private fun randomSanitizeUrl(rnd: Random): String {
        return when (rnd.nextInt(100)) {
            in 0..69 -> composeSanitizeUrl(rnd)
            in 70..84 -> {
                // mutate a composed URL: drop the slashes, drop the scheme, or junk it up
                val base = composeSanitizeUrl(rnd)
                when (rnd.nextInt(4)) {
                    0 -> base.replace("://", ":")
                    1 -> base.substringAfter("://", base)
                    2 -> " " + base + " "
                    else -> base + rnd.nextInt(1000).toString()
                }
            }
            else -> {
                val n = rnd.nextInt(0, 41)
                (0 until n).joinToString("") { SOUP_ALPHABET.random(rnd) }
            }
        }
    }

    private val TEXT_WORDS = listOf(
        "hello", "see", "check", "this", "out", "visit", "link", "点击", "看看",
        "http", "https", "www", "com", "a", "x", " ", "  ", "\t", "\n", "。",
        "，", "!", "?", "(", ")", "[", "]", "{", "}", "\"", "'", ".", ",", ";",
        ":", "/", "<meta>", "<META>", "<meta", "meta>", "screenshot",
        "Screen_Record", "ſ", "K", "İ", "σ", "ς"
    )
    private val TEXT_URLS = listOf(
        "https://example.com/a", "http://example.com/a", "www.example.com/x",
        "WWW.EXAMPLE.COM/X", "https://EXAMPLE.COM/A", "http://127.0.0.1/x",
        "https://192.168.1.1/x", "https://example.com/a.", "https://example.com/a)",
        "https://example.com/a,", "https://example.com/a\"",
        "https://example.com/a'", "https://example.com/a]",
        "https://example.com/a}", "https://example.com/a?b=c&d=e",
        "https://example.com/a#f", "http://", "https://", "http:/x",
        "http://exa mple.com", "www.", "www.example.com", "https://foo.local/x",
        "https://example.com/a...", "https://example.com/a...",
        "https://example.com/a).,", "HTTP://EXAMPLE.COM/A",
        "HtTpS://ExAmPlE.cOm/MiXeD", "https://example.com/a b",
        "www.example.com/a_(b)", "https://example.com/a[b]",
        "https://example.com/a%20b", "https://user@example.com/a",
        "https://example.com:8080/a", "https://example.com:22/a"
    )

    private fun randomFirstHttpUrlText(rnd: Random): String {
        val n = rnd.nextInt(0, 9)
        val sb = StringBuilder()
        repeat(n) { k ->
            if (k > 0 && rnd.nextInt(100) < 80) sb.append(' ')
            sb.append(if (rnd.nextInt(100) < 45) TEXT_URLS.random(rnd) else TEXT_WORDS.random(rnd))
        }
        var text = sb.toString()
        if (rnd.nextInt(100) < 25) {
            // trailing <meta> block: everything after the LAST <meta> is dropped
            val pos = rnd.nextInt(text.length + 1)
            text = text.substring(0, pos) + "<meta>" + TEXT_URLS.random(rnd) + text.substring(pos)
        }
        if (rnd.nextInt(100) < 15) text = "  " + text + "  "
        if (rnd.nextInt(100) < 10) text = ""
        if (rnd.nextInt(100) < 10) text = "   "
        return text
    }

    private fun randomDisplayHostUrl(rnd: Random): String {
        return when (rnd.nextInt(100)) {
            in 0..74 -> {
                val base = composeSanitizeUrl(rnd)
                if (rnd.nextInt(100) < 20) {
                    // very long host: take(96) truncation
                    "https://" + "a".repeat(rnd.nextInt(90, 130)) + ".example.com/x"
                } else {
                    base
                }
            }
            in 75..89 -> {
                val host = SANITIZE_HOSTS.random(rnd)
                "https://www.$host/x"
            }
            else -> {
                val n = rnd.nextInt(0, 30)
                (0 until n).joinToString("") { SOUP_ALPHABET.random(rnd) }
            }
        }
    }

    private val META_KEYS = listOf(
        "og:title", "twitter:title", "og:description", "description",
        "twitter:description", "og:image", "twitter:image", "og:site_name"
    )
    private val META_VALUES = listOf(
        "Hello", "Hello World", "a  b", "  spaced  ", "a &amp; b", "a &lt; b",
        "a &gt; b", "say &quot;hi&quot;", "it&#39;s", "&amp;lt;", "&amp;amp;",
        "a>b", "a<b", "x\ty\nz", "a/b", "title with 'quotes'", "emoji \uD83D\uDE00",
        "", " ", "a".repeat(300), "tab\there", "line\nbreak", "100% sure",
        "a_b", "Café", "naïve", "a=b&c=d", "x".repeat(250)
    )
    private val IMAGE_REFS = listOf(
        "/img.png", "img.png", "../up.png", "./here.png", "//cdn.example.com/i.png",
        "https://cdn.example.com/i.png", "http://192.168.1.1/i.png", "?q=1", "#f",
        "", "   ", "a/./b/../c.png", "a%2fb.png", "d e.png", "a b.png",
        "/abs%20path.png", "https://example.com:8080/i.png", "/a/b/c.png",
        "../../..", "..", ".", "/"
    )

    private fun randomMetaTag(rnd: Random, key: String, value: String): String {
        val quote = if (rnd.nextInt(100) < 50) "\"" else "'"
        val attrName = if (rnd.nextBoolean()) "property" else "name"
        val sp1 = if (rnd.nextInt(100) < 30) " " else ""
        val sp2 = if (rnd.nextInt(100) < 30) " " else ""
        val sp3 = if (rnd.nextInt(100) < 20) " " else ""
        val tagCase = rnd.nextInt(100) < 15
        val open = if (tagCase) "<META" else "<meta"
        val attrCased = if (rnd.nextInt(100) < 15) attrName.uppercase() else attrName
        val keyCased = if (rnd.nextInt(100) < 15) key.uppercase() else key
        val contentCased = if (rnd.nextInt(100) < 15) "CONTENT" else "content"
        val extra = if (rnd.nextInt(100) < 20) " data-x=\"1\"" else ""
        val close = if (rnd.nextInt(100) < 30) " />" else ">"
        return if (rnd.nextBoolean()) {
            "$open$extra $attrCased$sp1=$sp2$quote$keyCased$quote$sp3$contentCased$sp1=$sp2$quote$value$quote$close"
        } else {
            "$open$extra $contentCased$sp1=$sp2$quote$value$quote$sp3$attrCased$sp1=$sp2$quote$keyCased$quote$close"
        }
    }

    private fun randomHtml(rnd: Random): String {
        val sb = StringBuilder()
        if (rnd.nextInt(100) < 80) sb.append("<html><head>")
        val nMeta = rnd.nextInt(0, 5)
        repeat(nMeta) {
            if (rnd.nextInt(100) < 15) sb.append("junk ")
            val key = META_KEYS.random(rnd)
            val value = META_VALUES.random(rnd)
            sb.append(randomMetaTag(rnd, key, value))
        }
        if (rnd.nextInt(100) < 50) {
            val title = META_VALUES.random(rnd)
            val attr = if (rnd.nextInt(100) < 30) " class=\"t\"" else ""
            val open = if (rnd.nextInt(100) < 15) "<TITLE$attr>" else "<title$attr>"
            val close = if (rnd.nextInt(100) < 15) "</TITLE>" else "</title>"
            sb.append(open).append(title).append(close)
        }
        if (rnd.nextInt(100) < 15) sb.append("<meta property=\"og:title\" content=\"a>b\">")
        if (rnd.nextInt(100) < 70) sb.append("</head>")
        if (rnd.nextInt(100) < 30) sb.append("<body>after head ")
        if (rnd.nextInt(100) < 20) {
            sb.append("<meta property=\"og:title\" content=\"too late\">")
        }
        if (rnd.nextInt(100) < 15) sb.append("</body></html>")
        return sb.toString()
    }

    private fun randomResolveCase(rnd: Random): Pair<String, String> {
        val base = randomPreviewBase(rnd)
        val ref = IMAGE_REFS.random(rnd)
        val html = "<html><head><meta property=\"og:image\" content=\"" + ref + "\"></head></html>"
        return Pair(base, html)
    }

    private fun randomPreviewBase(rnd: Random): String {
        val hosts = listOf("example.com", "www.example.com", "sub.example.org", "EXAMPLE.COM")
        return "https://" + hosts.random(rnd) + "/a/b"
    }

    @Test
    fun sanitizeUrlRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val url = randomSanitizeUrl(rnd)
            assertEquals(sanitizeUrlOracle(url), LinkPreviewPolicy.sanitizeUrl(url), "url=<$url>")
        }
    }

    @Test
    fun firstHttpUrlRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val text = randomFirstHttpUrlText(rnd)
            assertEquals(firstHttpUrlOracle(text), LinkPreviewPolicy.firstHttpUrl(text), "text=<$text>")
        }
    }

    @Test
    fun displayHostRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val url = randomDisplayHostUrl(rnd)
            assertEquals(displayHostOracle(url), LinkPreviewPolicy.displayHost(url), "url=<$url>")
        }
    }

    @Test
    fun parseHtmlPreviewRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val base = randomPreviewBase(rnd)
            val html = randomHtml(rnd)
            val expected = parseHtmlPreviewOracle(base, html)
            val actual = LinkPreviewPolicy.parseHtmlPreview(base, html)
            assertEquals(expected, actual, "base=<$base> html=<$html>")
            assertEquals(
                isUsefulOracle(expected),
                LinkPreviewPolicy.isUseful(actual),
                "base=<$base> html=<$html>"
            )
        }
    }

    @Test
    fun resolveImageUrlRandomOracle() {
        val rnd = Random(42)
        repeat(200) {
            val (base, html) = randomResolveCase(rnd)
            val expected = parseHtmlPreviewOracle(base, html)
            val actual = LinkPreviewPolicy.parseHtmlPreview(base, html)
            assertEquals(expected.imageUrl, actual.imageUrl, "base=<$base> html=<$html>")
        }
    }

    @Test
    fun sanitizeInvariantsRandom() {
        val rnd = Random(42)
        repeat(200) {
            when (rnd.nextInt(4)) {
                0 -> {
                    val url = randomSanitizeUrl(rnd)
                    val out = LinkPreviewPolicy.sanitizeUrl(url)
                    if (out != null) {
                        assertEquals(url, out, "sanitize must return the input verbatim url=<$url>")
                        assertEquals(
                            out,
                            LinkPreviewPolicy.sanitizeUrl(out),
                            "sanitize must be idempotent url=<$url>"
                        )
                        assertTrue(
                            out.startsWith("http://", ignoreCase = true) ||
                                out.startsWith("https://", ignoreCase = true),
                            "sanitize result must be http(s) url=<$url>"
                        )
                    }
                }
                1 -> {
                    val text = randomFirstHttpUrlText(rnd)
                    val out = LinkPreviewPolicy.firstHttpUrl(text)
                    if (out != null) {
                        assertEquals(
                            out,
                            LinkPreviewPolicy.sanitizeUrl(out),
                            "firstHttpUrl result must pass sanitizeUrl text=<$text>"
                        )
                    }
                }
                2 -> {
                    val url = randomDisplayHostUrl(rnd)
                    assertTrue(
                        LinkPreviewPolicy.displayHost(url).length <= 96,
                        "displayHost truncates to 96 url=<$url>"
                    )
                }
                else -> {
                    val base = randomPreviewBase(rnd)
                    val html = randomHtml(rnd)
                    val preview = LinkPreviewPolicy.parseHtmlPreview(base, html)
                    assertTrue(
                        (preview.title?.length ?: 0) <= 220,
                        "title cap 220 base=<$base> html=<$html>"
                    )
                    assertTrue(
                        (preview.description?.length ?: 0) <= 400,
                        "description cap 400 base=<$base> html=<$html>"
                    )
                    assertTrue(
                        (preview.siteName?.length ?: 0) <= 120,
                        "siteName cap 120 base=<$base> html=<$html>"
                    )
                    val imageUrl = preview.imageUrl
                    if (imageUrl != null) {
                        assertEquals(
                            imageUrl,
                            LinkPreviewPolicy.sanitizeUrl(imageUrl),
                            "imageUrl must pass sanitizeUrl base=<$base> html=<$html>"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // sanitizeUrl: public hosts pass through verbatim, private / reserved /
        // malformed hosts are rejected (IANA special-purpose registries).
        assertEquals("https://example.com/a", LinkPreviewPolicy.sanitizeUrl("https://example.com/a"))
        assertEquals("HTTP://EXAMPLE.COM/A", LinkPreviewPolicy.sanitizeUrl("HTTP://EXAMPLE.COM/A"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://localhost/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://LOCALHOST/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://2130706433/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0x7f000001/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0X7F000001/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0177.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://10.0.0.5/a"))
        assertEquals("http://172.15.0.1/a", LinkPreviewPolicy.sanitizeUrl("http://172.15.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://172.16.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://172.31.255.255/a"))
        assertEquals("http://172.32.0.1/a", LinkPreviewPolicy.sanitizeUrl("http://172.32.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://169.254.169.254/a"))
        assertEquals("http://169.253.1.1/a", LinkPreviewPolicy.sanitizeUrl("http://169.253.1.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.168.1.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.0.0.1/a"))
        assertEquals("http://192.0.1.1/a", LinkPreviewPolicy.sanitizeUrl("http://192.0.1.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.0.2.1/a"))
        assertEquals("http://192.0.3.1/a", LinkPreviewPolicy.sanitizeUrl("http://192.0.3.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://198.18.0.1/a"))
        assertEquals("http://198.20.0.1/a", LinkPreviewPolicy.sanitizeUrl("http://198.20.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://198.51.100.1/a"))
        assertEquals("http://198.51.101.1/a", LinkPreviewPolicy.sanitizeUrl("http://198.51.101.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://203.0.113.1/a"))
        assertEquals("http://203.0.114.1/a", LinkPreviewPolicy.sanitizeUrl("http://203.0.114.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://224.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://240.0.0.1/a"))
        assertEquals("http://223.255.255.255/a", LinkPreviewPolicy.sanitizeUrl("http://223.255.255.255/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://100.64.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://100.127.255.255/a"))
        assertEquals("http://100.128.0.1/a", LinkPreviewPolicy.sanitizeUrl("http://100.128.0.1/a"))
        assertEquals("http://100.63.0.1/a", LinkPreviewPolicy.sanitizeUrl("http://100.63.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0.0.0.0/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0.1.2.3/a"))
        assertEquals("http://1.2.3.4:8080/a", LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4:8080/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4:22/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4:65536/a"))
        assertEquals("http://1.2.3.4:080/a", LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4:080/a"))
        assertEquals("http://example.com:443/a", LinkPreviewPolicy.sanitizeUrl("http://example.com:443/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("ftp://example.com/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("javascript:alert(1)"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://user@example.com/a"))
        assertEquals("https://example.com./a", LinkPreviewPolicy.sanitizeUrl("https://example.com./a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://foo.local/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://foo.internal/a"))
        assertEquals("https://foo.localhost/a", LinkPreviewPolicy.sanitizeUrl("https://foo.localhost/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[::1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[::]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[fe80::1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[fc00::1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[fd12:3456::1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[ff02::1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[2001:db8::1]/a"))
        assertEquals("https://[2606:4700:4700::1111]/a", LinkPreviewPolicy.sanitizeUrl("https://[2606:4700:4700::1111]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[::ffff:127.0.0.1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[::ffff:7f00:1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[::2]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://[::127.0.0.1]/a"))
        assertEquals("https://[1:2:3:4:5:6:7:8]/a", LinkPreviewPolicy.sanitizeUrl("https://[1:2:3:4:5:6:7:8]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://example.com/a b"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://exa_mple.com/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("https://example.com/%zz"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://1.2.3/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4.5/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://a.1/a"))
        assertEquals("http://1.a/a", LinkPreviewPolicy.sanitizeUrl("http://1.a/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0x7f.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.256/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.01/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://256.1.1.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[fe80::1%25eth0]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::ffff:0:1]/a"))
        assertEquals("https://example.com/a/./b/../c", LinkPreviewPolicy.sanitizeUrl("https://example.com/a/./b/../c"))
        // firstHttpUrl: first regex match only — a SSRF-rejected first URL
        // yields null even when a later URL in the text is fine.
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("see https://example.com/a please"))
        assertEquals("https://www.example.com/x", LinkPreviewPolicy.firstHttpUrl("www.example.com/x"))
        assertNull(LinkPreviewPolicy.firstHttpUrl("no links here"))
        assertNull(LinkPreviewPolicy.firstHttpUrl("ftp://example.com/f"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("visit https://example.com/a. next"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("see (https://example.com/a) ok"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("https://example.com/a https://example.com/b"))
        assertNull(LinkPreviewPolicy.firstHttpUrl("text <meta> https://example.com/late"))
        assertEquals("https://example.com/early", LinkPreviewPolicy.firstHttpUrl("https://example.com/early <meta> https://example.com/late"))
        assertEquals("HTTP://EXAMPLE.COM/A", LinkPreviewPolicy.firstHttpUrl("HTTP://EXAMPLE.COM/A"))
        assertEquals("https://WWW.EXAMPLE.COM/x", LinkPreviewPolicy.firstHttpUrl("check WWW.EXAMPLE.COM/x"))
        assertNull(LinkPreviewPolicy.firstHttpUrl("https://127.0.0.1/x"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("https://example.com/a, and more"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("https://example.com/a\"quoted\""))
        assertEquals("http://exa", LinkPreviewPolicy.firstHttpUrl("http://exa mple.com"))
        assertEquals("https://www.example.com/a_(b", LinkPreviewPolicy.firstHttpUrl("www.example.com/a_(b)"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("https://example.com/a..."))
        assertEquals("https://example.com/a", LinkPreviewPolicy.firstHttpUrl("  https://example.com/a  "))
        assertNull(LinkPreviewPolicy.firstHttpUrl(""))
        assertNull(LinkPreviewPolicy.firstHttpUrl("   "))
        assertNull(LinkPreviewPolicy.firstHttpUrl("https://foo.local/x and https://example.com/y"))
        // displayHost: strips a lowercase www. prefix, falls back to the raw
        // URL when there is no host, truncates to 96 chars.
        assertEquals("example.com", LinkPreviewPolicy.displayHost("https://www.example.com/a"))
        assertEquals("WWW.example.com", LinkPreviewPolicy.displayHost("https://WWW.example.com/a"))
        assertEquals("example.com", LinkPreviewPolicy.displayHost("https://example.com/a"))
        assertEquals("not a url", LinkPreviewPolicy.displayHost("not a url"))
        assertEquals("example.com", LinkPreviewPolicy.displayHost("https://example.com:8080/a"))
        assertEquals("a".repeat(96), LinkPreviewPolicy.displayHost("https://" + "a".repeat(100) + ".com/x"))
        assertEquals("127.0.0.1", LinkPreviewPolicy.displayHost("http://127.0.0.1/x"))
        assertEquals("[::1]", LinkPreviewPolicy.displayHost("http://[::1]/x"))
        assertEquals("http://exa_mple.com/x", LinkPreviewPolicy.displayHost("http://exa_mple.com/x"))
        assertEquals("", LinkPreviewPolicy.displayHost(""))
        assertEquals("b".repeat(96), LinkPreviewPolicy.displayHost("https://www." + "b".repeat(100) + ".example.com/x"))
        assertEquals("https://example.com/a b", LinkPreviewPolicy.displayHost("https://example.com/a b"))
        // parseHtmlPreview: og/twitter/title fallback chain, entity decoding,
        // length caps, relative-image resolution through sanitizeUrl.
        val preview0 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"Hello\" /><meta property=\"og:image\" content=\"/img.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "Hello", description = null, imageUrl = "https://example.com/img.png", siteName = "example.com"), preview0)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview0))
        val preview1 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta name=\"twitter:title\" content=\"Twit\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "Twit", description = null, imageUrl = null, siteName = "example.com"), preview1)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview1))
        val preview2 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><title>Plain Title</title></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "Plain Title", description = null, imageUrl = null, siteName = "example.com"), preview2)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview2))
        val preview3 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"a &amp; b\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "a & b", description = null, imageUrl = null, siteName = "example.com"), preview3)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview3))
        val preview4 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"&amp;lt;\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "<", description = null, imageUrl = null, siteName = "example.com"), preview4)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview4))
        val preview5 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"&amp;amp;\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "&amp;", description = null, imageUrl = null, siteName = "example.com"), preview5)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview5))
        val preview6 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"a  b\tc\nd\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "a b c d", description = null, imageUrl = null, siteName = "example.com"), preview6)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview6))
        val preview7 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"" + "a".repeat(300) + "\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "a".repeat(220), description = null, imageUrl = null, siteName = "example.com"), preview7)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview7))
        val preview8 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"img.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = "https://example.com/a/img.png", siteName = "example.com"), preview8)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview8))
        val preview9 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"../up.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = "https://example.com/up.png", siteName = "example.com"), preview9)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview9))
        val preview10 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"//cdn.example.com/i.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = "https://cdn.example.com/i.png", siteName = "example.com"), preview10)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview10))
        val preview11 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"http://192.168.1.1/i.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = null, siteName = "example.com"), preview11)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview11))
        val preview12 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = null, siteName = "example.com"), preview12)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview12))
        val preview13 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:site_name\" content=\"My Site\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = null, imageUrl = null, siteName = "My Site"), preview13)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview13))
        val preview14 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"in head\" /></head><meta property=\"og:title\" content=\"too late\" /></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "in head", description = null, imageUrl = null, siteName = "example.com"), preview14)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview14))
        val preview15 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"a>b\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "a>b", description = null, imageUrl = null, siteName = "example.com"), preview15)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview15))
        val preview16 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"a\" content=\"b\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "b", description = null, imageUrl = null, siteName = "example.com"), preview16)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview16))
        val preview17 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta name=\"description\" content=\"D1\" /><meta name=\"twitter:description\" content=\"D2\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = "D1", imageUrl = null, siteName = "example.com"), preview17)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview17))
        val preview18 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:description\" content=\"OD\" /><meta name=\"description\" content=\"D1\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = "OD", imageUrl = null, siteName = "example.com"), preview18)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview18))
        val preview19 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta name=\"twitter:description\" content=\"TD\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = "TD", imageUrl = null, siteName = "example.com"), preview19)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview19))
        val preview20 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"d e.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = null, siteName = "example.com"), preview20)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview20))
        val preview21 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"   \" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = null, imageUrl = null, siteName = "example.com"), preview21)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview21))
        val preview22 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"" + "b".repeat(500) + "\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "b".repeat(220), description = null, imageUrl = null, siteName = "example.com"), preview22)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview22))
        val preview23 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:description\" content=\"" + "d".repeat(500) + "\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = "d".repeat(400), imageUrl = null, siteName = "example.com"), preview23)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview23))
        val preview24 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:site_name\" content=\"" + "s".repeat(200) + "\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = null, description = null, imageUrl = null, siteName = "s".repeat(120)), preview24)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview24))
        val preview25 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"it&#39;s\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "it's", description = null, imageUrl = null, siteName = "example.com"), preview25)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview25))
        val preview26 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"say &quot;hi&quot;\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "say \"hi\"", description = null, imageUrl = null, siteName = "example.com"), preview26)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview26))
        val preview27 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"a &gt; b\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "a > b", description = null, imageUrl = null, siteName = "example.com"), preview27)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview27))
        val preview28 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><title>multi\nline\ttitle</title></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "multi line title", description = null, imageUrl = null, siteName = "example.com"), preview28)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview28))
        val preview29 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"first\" /><meta property=\"og:title\" content=\"second\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a", title = "first", description = null, imageUrl = null, siteName = "example.com"), preview29)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview29))
        val preview30 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"/a/b/c.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = "https://example.com/a/b/c.png", siteName = "example.com"), preview30)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview30))
        // java.net.URI.resolve does NOT do RFC 3986 dot-segment removal:
        // merged "/a/b/a/./b/../c.png" resolves to "/a/a/c.png".
        val preview31 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a/b", "<html><head><meta property=\"og:image\" content=\"a/./b/../c.png\" /></head></html>")
        assertEquals(Preview(url = "https://example.com/a/b", title = null, description = null, imageUrl = "https://example.com/a/a/c.png", siteName = "example.com"), preview31)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview31))
        val preview32 = LinkPreviewPolicy.parseHtmlPreview("https://example.com/a", "<html><head><meta property=\"og:title\" content=\"x\" /></head></html>trailing")
        assertEquals(Preview(url = "https://example.com/a", title = "x", description = null, imageUrl = null, siteName = "example.com"), preview32)
        assertEquals(true, LinkPreviewPolicy.isUseful(preview32))
        val preview33 = LinkPreviewPolicy.parseHtmlPreview("https://www.example.com/a", "<html><head></head></html>")
        assertEquals(Preview(url = "https://www.example.com/a", title = null, description = null, imageUrl = null, siteName = "example.com"), preview33)
        assertEquals(false, LinkPreviewPolicy.isUseful(preview33))
    }
}
