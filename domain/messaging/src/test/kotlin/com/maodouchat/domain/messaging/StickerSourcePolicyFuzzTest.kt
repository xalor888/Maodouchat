package com.maodouchat.domain.messaging

import java.net.IDN
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// StickerSourcePolicy.resolve restated by hand. Ground truth, calibrated
// against real OkHttp 4.12.0 (see scratch/fuzz187/calib*_187.log):
//   - Kotlin trim strips Java-whitespace ends; blank raw -> null.
//   - Any absolute URL with a non-http(s) scheme fails to parse, so it
//     is always null (HttpUrl.parse rejects every other scheme).
//   - "https:foo" against a base keeps the base authority and resolves
//     "foo" as a relative path; "https:/a" is an absolute path.
//   - Authority: skip any run of '/' and '\' after "//", then read to
//     the first '/', '\', '?', '#'; userinfo splits at the last '@',
//     host/port at the first ':' (bracketed hosts are IPv6 literals).
//   - Host: percent-decode, then IDN.toASCII, lowercase (US), empty or
//     a char <= 0x20 / >= 0x7F / in "#%/:?@[\\]" -> null. A ':' in the
//     host means IPv6: decodeIpv6, IPv4-mapped forms render as dotted
//     quads, other forms render compressed lowercase hex.
//   - Path segments split on '/' and '\'; dot segments are ".", "..",
//     "%2e", ".%2e", "%2e.", "%2e%2e" (case-insensitive); ".." pops,
//     a trailing "." or ".." leaves a trailing "/".
//   - Canonicalize strips \t \n \f \r anywhere, percent-encodes the
//     per-component set plus controls/0x7F/non-ASCII (fragment keeps
//     non-ASCII raw); lone surrogates become '?' first.

internal data class StickerCase187(val raw: String, val base: String)

private val HEX187 = "0123456789ABCDEF"

internal fun isJavaWhitespace187(c: Char): Boolean = c.code in 0x09..0x0D ||
    c.code in 0x1C..0x1F || c == ' ' || c == '\u00a0' || c == '\u1680' ||
    c.code in 0x2000..0x200A || c == '\u2028' || c == '\u2029' ||
    c == '\u202f' || c == '\u205f' || c == '\u3000'

internal fun ktTrim187(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isJavaWhitespace187(s[start])) start++
    while (end > start && isJavaWhitespace187(s[end - 1])) end--
    return s.substring(start, end)
}

internal fun parseHexDigit187(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private fun appendUtf8Percent187(sb: StringBuilder, cp: Int) {
    val b0: Int
    val b1: Int
    val b2: Int
    val b3: Int
    val n: Int
    when {
        cp < 0x80 -> { b0 = cp; b1 = -1; b2 = -1; b3 = -1; n = 1 }
        cp < 0x800 -> { b0 = 0xC0 or (cp shr 6); b1 = 0x80 or (cp and 0x3F); b2 = -1; b3 = -1; n = 2 }
        cp < 0x10000 -> {
            b0 = 0xE0 or (cp shr 12); b1 = 0x80 or ((cp shr 6) and 0x3F)
            b2 = 0x80 or (cp and 0x3F); b3 = -1; n = 3
        }
        else -> {
            b0 = 0xF0 or (cp shr 18); b1 = 0x80 or ((cp shr 12) and 0x3F)
            b2 = 0x80 or ((cp shr 6) and 0x3F); b3 = 0x80 or (cp and 0x3F); n = 4
        }
    }
    val bytes = intArrayOf(b0, b1, b2, b3)
    for (k in 0 until n) {
        val b = bytes[k]
        sb.append('%').append(HEX187[(b ushr 4) and 0xF]).append(HEX187[b and 0xF])
    }
}

private fun appendUtf8Bytes187(bytes: ArrayList<Byte>, cp: Int) {
    when {
        cp < 0x80 -> bytes.add(cp.toByte())
        cp < 0x800 -> {
            bytes.add((0xC0 or (cp shr 6)).toByte())
            bytes.add((0x80 or (cp and 0x3F)).toByte())
        }
        cp < 0x10000 -> {
            bytes.add((0xE0 or (cp shr 12)).toByte())
            bytes.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
            bytes.add((0x80 or (cp and 0x3F)).toByte())
        }
        else -> {
            bytes.add((0xF0 or (cp shr 18)).toByte())
            bytes.add((0x80 or ((cp shr 12) and 0x3F)).toByte())
            bytes.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
            bytes.add((0x80 or (cp and 0x3F)).toByte())
        }
    }
}

// Percent-encode one component. Lone surrogates are replaced by '?'
// and then encoded like any other '?'.
internal fun canonicalize187(s: String, encodeSet: String, unicodeAllowed: Boolean): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = if (cp >= 0x10000) 2 else 1
        i += n
        if (cp in 0xD800..0xDFFF) {
            if (unicodeAllowed) sb.append('?') else sb.append("%3F")
            continue
        }
        val ch = s[i - n]
        if (ch == '\t' || ch == '\n' || ch == '\u000C' || ch == '\r') continue
        if (cp < 0x20 || cp == 0x7F || (cp >= 0x80 && !unicodeAllowed) ||
            (n == 1 && ch in encodeSet)
        ) {
            appendUtf8Percent187(sb, cp)
        } else {
            sb.append(s, i - n, i)
        }
    }
    return sb.toString()
}

// OkHttp percent-decode: %XX triples become raw bytes, other code
// points become their UTF-8 bytes (lone surrogates become '?'), the
// whole buffer is then read back as UTF-8 with U+FFFD replacement.
internal fun percentDecode187(s: String): String {
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val d1 = parseHexDigit187(s[i + 1])
            val d2 = parseHexDigit187(s[i + 2])
            if (d1 != -1 && d2 != -1) {
                bytes.add(((d1 shl 4) + d2).toByte())
                i += 3
                continue
            }
        }
        val cp = s.codePointAt(i)
        val n = if (cp >= 0x10000) 2 else 1
        i += n
        if (cp in 0xD800..0xDFFF) bytes.add('?'.code.toByte()) else appendUtf8Bytes187(bytes, cp)
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

private fun containsInvalidHostnameAsciiCodes187(s: String): Boolean {
    for (i in s.indices) {
        val c = s[i]
        if (c.code <= 0x20 || c.code >= 0x7F) return true
        if (c in "#%/:?@[\\]") return true
    }
    return false
}

private fun decodeIpv4Suffix187(
    input: String,
    pos: Int,
    limit: Int,
    address: ByteArray,
    addressOffset: Int
): Boolean {
    var offset = addressOffset
    var i = pos
    while (i < limit) {
        if (offset == address.size) return false
        if (offset != addressOffset) {
            if (input[i] != '.') return false
            i++
        }
        var value = 0
        val start = i
        while (i < limit) {
            val c = input[i]
            if (c < '0' || c > '9') break
            if (value == 0 && start != i) return false
            value = value * 10 + (c - '0')
            if (value > 255) return false
            i++
        }
        if (i - start == 0) return false
        address[offset++] = value.toByte()
    }
    return offset == addressOffset + 4
}

private fun decodeIpv6_187(input: String): ByteArray? {
    val address = ByteArray(16)
    var b = 0
    var compress = -1
    var groupStart = -1
    var i = 0
    val limit = input.length
    while (i < limit) {
        if (b == address.size) return null
        if (i + 2 <= limit && input.startsWith("::", i)) {
            if (compress != -1) return null
            i += 2
            b += 2
            compress = b
            if (i == limit) break
            // fall through to read the group after "::"
        } else if (b != 0) {
            if (input.startsWith(":", i)) {
                i++
                // fall through to read the group after ":"
            } else if (input.startsWith(".", i)) {
                if (!decodeIpv4Suffix187(input, groupStart, limit, address, b - 2)) return null
                b += 2
                break
            } else {
                return null
            }
        }
        var value = 0
        groupStart = i
        while (i < limit) {
            val hex = parseHexDigit187(input[i])
            if (hex == -1) break
            value = (value shl 4) + hex
            i++
        }
        val groupLength = i - groupStart
        if (groupLength == 0 || groupLength > 4) return null
        address[b++] = ((value ushr 8) and 0xFF).toByte()
        address[b++] = (value and 0xFF).toByte()
    }
    if (b != address.size) {
        if (compress == -1) return null
        address.copyInto(address, address.size - (b - compress), compress, b)
        address.fill(0, compress, compress + (address.size - b))
    }
    return address
}

private fun inet6AddressToAscii187(address: ByteArray): String {
    var best = -1
    var bestLen = 0
    var start = 0
    while (start < address.size) {
        var i = start
        while (i < 16 && address[i] == 0.toByte() && address[i + 1] == 0.toByte()) i += 2
        val len = i - start
        if (len > bestLen && len >= 4) {
            best = start
            bestLen = len
        }
        start = i + 2
    }
    val sb = StringBuilder()
    var i = 0
    while (i < address.size) {
        if (i == best) {
            sb.append(':')
            i += bestLen
            if (i == 16) sb.append(':')
            continue
        }
        if (i > 0) sb.append(':')
        val group = ((address[i].toInt() and 0xFF) shl 8) or (address[i + 1].toInt() and 0xFF)
        sb.append(group.toString(16))
        i += 2
    }
    return sb.toString()
}

internal fun toCanonicalHost187(host: String): String? {
    if (':' in host) {
        val bytes = if (host.startsWith("[") && host.endsWith("]")) {
            decodeIpv6_187(host.substring(1, host.length - 1))
        } else {
            decodeIpv6_187(host)
        } ?: return null
        val inet = try {
            InetAddress.getByAddress(bytes)
        } catch (e: UnknownHostException) {
            return null
        }
        val raw = inet.address
        return if (raw.size == 16) inet6AddressToAscii187(raw) else inet.hostAddress
    }
    val ascii = try {
        IDN.toASCII(host)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val lower = ascii.lowercase(Locale.US)
    if (lower.isEmpty()) return null
    if (containsInvalidHostnameAsciiCodes187(lower)) return null
    return lower
}

private const val PATH_ENCODE_SET187 = " \"<>^`{}|"
private const val QUERY_ENCODE_SET187 = " \"'<>"
private const val USERINFO_ENCODE_SET187 = " \"':;<=>@[]^`{}|/\\?#"

internal data class Url187(
    val scheme: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int, // -1 = default
    val pathSegments: List<String>,
    val query: String?,
    val fragment: String?
) {
    fun effectivePort(): Int = if (port == -1) defaultPort187(scheme) else port
    fun toUrlString(): String {
        val sb = StringBuilder()
        sb.append(scheme).append("://")
        if (username.isNotEmpty() || password.isNotEmpty()) {
            sb.append(username)
            if (password.isNotEmpty()) sb.append(':').append(password)
            sb.append('@')
        }
        val bracketed = ':' in host
        if (bracketed) sb.append('[')
        sb.append(host)
        if (bracketed) sb.append(']')
        if (port != -1 && port != defaultPort187(scheme)) sb.append(':').append(port)
        sb.append('/').append(pathSegments.joinToString("/"))
        if (query != null) sb.append('?').append(query)
        if (fragment != null) sb.append('#').append(fragment)
        return sb.toString()
    }
}

internal fun defaultPort187(scheme: String): Int = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> -1
}

private fun isSchemeChar187(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '-' || c == '.'

// Returns the index of ':' when s starts with a valid scheme, else -1.
internal fun schemeDelimiterOffset187(s: String): Int {
    if (s.length < 2) return -1
    val c0 = s[0]
    if (c0 !in 'a'..'z' && c0 !in 'A'..'Z') return -1
    var i = 1
    while (i < s.length) {
        val c = s[i]
        if (isSchemeChar187(c)) {
            i++
            continue
        }
        if (c == ':') return i
        return -1
    }
    return -1
}

internal fun ktToInt187(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (s.length == 1) return null
    }
    var value = 0L
    while (i < s.length) {
        val c = s[i]
        if (c < '0' || c > '9') return null
        value = value * 10 + (c - '0')
        if (!neg && value > Int.MAX_VALUE) return null
        if (neg && value > 2147483648L) return null
        i++
    }
    val v = if (neg) -value else value
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) return null
    return v.toInt()
}

private fun parsePort187(s: String): Int? {
    if (s.isEmpty()) return -1
    val v = ktToInt187(s) ?: return null
    if (v < 1 || v > 65535) return null
    return v
}

private data class Authority187(val username: String, val password: String, val host: String, val port: Int)

private fun parseAuthority187(auth: String): Authority187? {
    var username = ""
    var password = ""
    var hostPort = auth
    val atIdx = auth.lastIndexOf('@')
    if (atIdx != -1) {
        val userinfo = auth.substring(0, atIdx)
        hostPort = auth.substring(atIdx + 1)
        val colonIdx = userinfo.indexOf(':')
        if (colonIdx != -1) {
            username = canonicalize187(userinfo.substring(0, colonIdx), USERINFO_ENCODE_SET187, false)
            password = canonicalize187(userinfo.substring(colonIdx + 1), USERINFO_ENCODE_SET187, false)
        } else {
            username = canonicalize187(userinfo, USERINFO_ENCODE_SET187, false)
        }
    }
    if (hostPort.startsWith("[")) {
        val closeIdx = hostPort.indexOf(']')
        if (closeIdx == -1) return null
        val inner = percentDecode187(hostPort.substring(1, closeIdx))
        val host = toCanonicalHost187("[$inner]") ?: return null
        val rest = hostPort.substring(closeIdx + 1)
        var port = -1
        if (rest.isNotEmpty()) {
            if (!rest.startsWith(":")) return null
            port = parsePort187(rest.substring(1)) ?: return null
        }
        return Authority187(username, password, host, port)
    }
    val colonIdx = hostPort.indexOf(':')
    val hostRaw = if (colonIdx != -1) hostPort.substring(0, colonIdx) else hostPort
    val port = if (colonIdx != -1) parsePort187(hostPort.substring(colonIdx + 1)) ?: return null else -1
    val host = toCanonicalHost187(percentDecode187(hostRaw)) ?: return null
    return Authority187(username, password, host, port)
}

private fun isDot187(seg: String): Boolean = seg == "." || seg.equals("%2e", ignoreCase = true)

private fun isDotDot187(seg: String): Boolean = seg == ".." ||
    seg.equals("%2e.", ignoreCase = true) || seg.equals(".%2e", ignoreCase = true) ||
    seg.equals("%2e%2e", ignoreCase = true)

// Push the segments of pathPart onto segments. absolute clears first;
// relative drops the last base segment first. An empty pathPart keeps
// the base path (handled by the caller).
private fun pushPath187(segments: ArrayList<String>, pathPart: String, absolute: Boolean) {
    if (absolute) segments.clear()
    val rawSegs = ArrayList<String>()
    var start = 0
    var i = 0
    while (i <= pathPart.length) {
        if (i == pathPart.length || pathPart[i] == '/' || pathPart[i] == '\\') {
            rawSegs.add(pathPart.substring(start, i))
            start = i + 1
        }
        i++
    }
    // The leading separator produces one empty first segment; drop it,
    // the join in toUrlString re-adds the slash.
    if (absolute && rawSegs.isNotEmpty() && rawSegs[0].isEmpty()) rawSegs.removeAt(0)
    for ((idx, raw) in rawSegs.withIndex()) {
        val last = idx == rawSegs.size - 1
        val seg = canonicalize187(raw, PATH_ENCODE_SET187, false)
        if (isDot187(seg)) {
            if (last) segments.add("")
        } else if (isDotDot187(seg)) {
            if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            if (last) segments.add("")
        } else {
            segments.add(seg)
        }
    }
}

private data class PathQueryFragment187(val pathPart: String, val query: String?, val fragment: String?)

private fun splitPathQueryFragment187(rest: String): PathQueryFragment187 {
    val hashIdx = rest.indexOf('#')
    val beforeFrag = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
    val fragment = if (hashIdx != -1) rest.substring(hashIdx + 1) else null
    val qIdx = beforeFrag.indexOf('?')
    val pathPart = if (qIdx != -1) beforeFrag.substring(0, qIdx) else beforeFrag
    val query = if (qIdx != -1) beforeFrag.substring(qIdx + 1) else null
    return PathQueryFragment187(pathPart, query, fragment)
}

// Parse rest (which starts with "//") under the given scheme: skip
// every '/' and '\', read the authority, then path/query/fragment.
private fun parseWithScheme187(scheme: String, rest: String): Url187? {
    var pos = 0
    while (pos < rest.length && (rest[pos] == '/' || rest[pos] == '\\')) pos++
    var end = pos
    while (end < rest.length && rest[end] != '/' && rest[end] != '\\' &&
        rest[end] != '?' && rest[end] != '#'
    ) end++
    val authority = parseAuthority187(rest.substring(pos, end)) ?: return null
    val pqf = splitPathQueryFragment187(rest.substring(end))
    val segments = ArrayList<String>()
    pushPath187(segments, pqf.pathPart, absolute = true)
    return Url187(
        scheme, authority.username, authority.password, authority.host, authority.port,
        segments,
        pqf.query?.let { canonicalize187(it, QUERY_ENCODE_SET187, false) },
        pqf.fragment?.let { canonicalize187(it, "", true) }
    )
}

// Absolute parse with no base: scheme, then any run of '/' and '\'
// is skipped and the rest is authority + path.
internal fun parseAbsolute187(s: String): Url187? {
    val schemeEnd = schemeDelimiterOffset187(s)
    if (schemeEnd == -1) return null
    val scheme = s.substring(0, schemeEnd).lowercase(Locale.US)
    if (scheme != "http" && scheme != "https") return null
    val rest = s.substring(schemeEnd + 1)
    return parseWithScheme187(scheme, rest)
}

private fun resolveRelative187(base: Url187, scheme: String, rest: String): Url187 {
    val pqf = splitPathQueryFragment187(rest)
    val segments: ArrayList<String>
    val query: String?
    if (pqf.pathPart.isEmpty()) {
        segments = ArrayList(base.pathSegments)
        query = pqf.query?.let { canonicalize187(it, QUERY_ENCODE_SET187, false) } ?: base.query
    } else {
        segments = ArrayList(base.pathSegments)
        if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
        pushPath187(segments, pqf.pathPart, absolute = pqf.pathPart[0] == '/' || pqf.pathPart[0] == '\\')
        query = pqf.query?.let { canonicalize187(it, QUERY_ENCODE_SET187, false) }
    }
    return Url187(
        scheme, base.username, base.password, base.host, base.port,
        segments, query, pqf.fragment?.let { canonicalize187(it, "", true) }
    )
}

internal fun resolveUrl187(base: Url187, raw: String): Url187? {
    val schemeEnd = schemeDelimiterOffset187(raw)
    if (schemeEnd != -1) {
        val scheme = raw.substring(0, schemeEnd).lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        val rest = raw.substring(schemeEnd + 1)
        if (rest.length >= 2 && rest[0] in "/\\" && rest[1] in "/\\") {
            return parseWithScheme187(scheme, rest)
        }
        return resolveRelative187(base, scheme, rest)
    }
    if (raw.length >= 2 && raw[0] in "/\\" && raw[1] in "/\\") {
        return parseWithScheme187(base.scheme, raw)
    }
    return resolveRelative187(base, base.scheme, raw)
}

internal fun stickerResolveOracle187(rawUrl: String, baseUrl: String): String? {
    val raw = ktTrim187(rawUrl)
    if (raw.isEmpty()) return null
    val base = parseAbsolute187(ktTrim187(baseUrl)) ?: return null
    if (base.scheme != "http" && base.scheme != "https") return null
    if (base.host.isEmpty()) return null
    val resolved = resolveUrl187(base, raw) ?: return null
    if (resolved.scheme != "http" && resolved.scheme != "https") return null
    if (resolved.host.isEmpty()) return null
    if (resolved.scheme != base.scheme || !resolved.host.equals(base.host, ignoreCase = true) ||
        resolved.effectivePort() != base.effectivePort()
    ) {
        return null
    }
    if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
    if (resolved.fragment != null) return null
    return resolved.toUrlString()
}

internal fun escJsonSticker187(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun stickerResultWireJson187(result: String?): String =
    result?.let { escJsonSticker187(it) } ?: "null"

// ---------- production ----------

internal fun stickerResolveProduction187(raw: String, base: String): String? =
    StickerSourcePolicy.resolve(raw, base)

// ---------- generators ----------

internal val STICKER_DEFAULT_BASE187 = "https://chat.example.com/api/v1/stickers/a.webp?x=1#f"

internal val STICKER_BASES187 = listOf(
    STICKER_DEFAULT_BASE187,
    "https://chat.example.com/api/v1/stickers/a.webp",
    "https://chat.example.com/",
    "https://chat.example.com",
    "http://chat.example.com:8080/a/b/c.webp",
    "https://chat.example.com:8443/a/",
    "https://user@chat.example.com/a/b.webp",
    "https://user:pw@chat.example.com/a/b.webp",
    "https://[::1]/a/b.webp",
    "https://[::1]:8443/a/",
    "https://chat.example.com./a/b.webp",
    "https://./a/b.webp",
    "https://chat.example.com/a//b.webp",
    "https://chat.example.com/a/b/",
    "HTTPS://CHAT.EXAMPLE.COM/A/B.webp",
    "https://chat.example.com/a/b.webp?y=2",
    "https://chat.example.com/a/b.webp#f",
    "https:chat.example.com/a/b.webp",
    "https://caf\u00e9.example.com/a.webp",
    "http://chat.example.com/a.webp",
    "https://chat.example.com/a/b.webp?y=2#g"
)

internal val STICKER_HOSTS187 = listOf(
    "chat.example.com", "other.example.com", "chat.example.com.", "caf\u00e9.example.com",
    "xn--caf-dma.example.com", "[::1]", "[2001:db8::1]", "[::ffff:192.168.0.1]",
    "[1:2:3:4:5:6:1.2.3.4]", "a_b.example.com", "192.168.0.1", "chat.example.com:80",
    "chat.example.com:443", "chat.example.com:8080", "chat.example.com:0",
    "chat.example.com:65536", "chat.example.com:abc", "chat.example.com:",
    "chat.example.com:080", "chat.example.com:+80", "CHAT.EXAMPLE.COM",
    "chat.example.com..", ".", "a..b", "%63hat.example.com", "ho st", "a^b",
    "a|b", "\u00e9", "\uD83D\uDE00", "a\u200bb", "a\u3002b", "a%2eb", "999.1.1.1",
    "1.2.3.400", "01.2.3.4", "0x7f.1", "[::ffff:c0a8:1]", "[fe80::1%25eth0]",
    "[v7.x]", "[]", "[::1", "chat.example.com:80:90", "chat.example.com: 80",
    "a%40b", "a%3Ab", "a%5Bb", "%2e", "%2e%2e", "a%2e%2e", "a\u00a0b"
)

internal val STICKER_PATHS187 = listOf(
    "/a.webp", "/a/b/c.webp", "/a//b", "/a/./b", "/a/../b", "/../a", "/a%2e/b",
    "/a%2Fb", "/a b", "/a\"b", "/a'b", "/a<b", "/a|b", "/a`b", "/a^b", "/a{}b",
    "/a\u00e9b", "/a\uD83D\uDE00b", "/a\u0001b", "/a\u007fb", "/a\u0085b",
    "/a\u200bb", "/a\ufeffb", "/a%2", "/a%", "/a%zz", "/a%2eb/c", "/%2e%2e/c",
    "/x%252e/c", "/%2E/c", "/a%2Fb/c", "/a%25/c", "/.", "/..", "/../", "/./",
    "/a/../../b", "/a/b/../../../c", "/", "//a", "\\\\a", "/a\\b/c", "/a\u0301b/c"
)

internal val STICKER_REL_PATHS187 = listOf(
    "b.webp", "sub/b.webp", "../b.webp", "./b.webp", "a/../../b", "..", ".",
    "../", "./", "a/..", "a/.", "a/../", "a/./", "a/b/c.webp", "a%2e/b",
    "a//b", "a\\b", "b.webp?x=1", "b.webp#f", "b.webp?x=1#f"
)

internal val STICKER_QUERIES187 = listOf(
    "x=1", "x=^`{|}", "x=\"'<>", "x=caf\u00e9", "x=\uD83D\uDE00", "x=a/b\\c",
    "x=a%2f", "x=a+b", "x=a;b=c&d=e", "", "x=\u0001", "x=\u007f", "x=\u0085"
)

internal val STICKER_FRAGS187 = listOf(
    "f", "caf\u00e9", "\uD83D\uDE00", "\u0001x", "\u007fx", "\u0085x", "\u000bx",
    "\u000cx", "\ud800", "\ud800\udc00", "\udc00\ud800", " ", "\"<>", "%^", "{}|", ""
)

internal val STICKER_USERINFOS187 = listOf(
    "user", "user:pw", "user:", ":pw", "user:pa:ss", "u s", "user;name",
    "user%40x", "", "\t", "user:\t", "user:name:pass"
)

internal val STICKER_SCHEMES187 = listOf(
    "https", "http", "HTTPS", "Http", "ftp", "a", "http+unix", "ws", "file", "data", "mailto"
)

private fun <T> List<T>.random187(rnd: Random): T = this[rnd.nextInt(size)]

private fun maybeQueryFrag187(rnd: Random): String {
    var s = ""
    if (rnd.nextInt(3) == 0) s += "?" + STICKER_QUERIES187.random187(rnd)
    if (rnd.nextInt(4) == 0) s += "#" + STICKER_FRAGS187.random187(rnd)
    return s
}

internal fun randomStickerRaw187(rnd: Random): String {
    return when (rnd.nextInt(100)) {
        in 0..24 -> {
            val scheme = STICKER_SCHEMES187.random187(rnd)
            val userinfo = if (rnd.nextInt(4) == 0) STICKER_USERINFOS187.random187(rnd) + "@" else ""
            val host = STICKER_HOSTS187.random187(rnd)
            "$scheme://$userinfo$host" + STICKER_PATHS187.random187(rnd) + maybeQueryFrag187(rnd)
        }
        in 25..39 -> {
            val userinfo = if (rnd.nextInt(4) == 0) STICKER_USERINFOS187.random187(rnd) + "@" else ""
            val host = STICKER_HOSTS187.random187(rnd)
            "//$userinfo$host" + STICKER_PATHS187.random187(rnd) + maybeQueryFrag187(rnd)
        }
        in 40..54 -> STICKER_PATHS187.random187(rnd) + maybeQueryFrag187(rnd)
        in 55..64 -> STICKER_REL_PATHS187.random187(rnd)
        in 65..71 -> when (rnd.nextInt(6)) {
            0 -> "?" + STICKER_QUERIES187.random187(rnd)
            1 -> "#" + STICKER_FRAGS187.random187(rnd)
            2 -> ""
            3 -> "?"
            4 -> "#"
            else -> "?x=1#f"
        }
        in 72..79 -> {
            val scheme = listOf("https", "http", "HTTPS").random187(rnd)
            val tail = when (rnd.nextInt(5)) {
                0 -> ""
                1 -> "?"
                2 -> "#g"
                3 -> "/a"
                else -> "foo" + maybeQueryFrag187(rnd)
            }
            "$scheme:$tail"
        }
        in 80..87 -> {
            val inner = randomStickerRaw187(rnd)
            val pad = listOf(" ", "\t", "\n", "\r", "\u00a0", "\u3000", "\u2003", "\u000b", "\u000c", "\u0001", "\u200b", "\ufeff").random187(rnd)
            val left = if (rnd.nextBoolean()) pad else ""
            val right = if (rnd.nextBoolean()) pad else ""
            left + inner + right
        }
        in 88..93 -> listOf(
            "\u00e9://chat.example.com/a.webp", "1http://chat.example.com/a.webp",
            "http ://chat.example.com/a.webp", "://chat.example.com/a.webp",
            "a:b", "mailto:foo@bar", "tel:+123", "https:chat.example.com/a.webp",
            "https:/chat.example.com/a.webp", "https:///chat.example.com/a.webp"
        ).random187(rnd)
        else -> {
            // Soup of URL punctuation and tricky code points.
            val alphabet = listOf(
                '/', '\\', '?', '#', '@', ':', '.', '%', 'a', ' ', '\t', '\n',
                '\u00e9', '\ud800', '\uD83D', '\uDE00', '[', ']', '-', '_', '~', '!'
            )
            val n = rnd.nextInt(13)
            buildString {
                repeat(n) { append(alphabet.random187(rnd)) }
            }
        }
    }
}

internal fun randomStickerCase187(rnd: Random): StickerCase187 {
    val base = STICKER_BASES187.random187(rnd)
    return StickerCase187(randomStickerRaw187(rnd), base)
}

internal fun stickerCaseWireJson187(c: StickerCase187): String =
    "{\"raw\":${escJsonSticker187(c.raw)},\"base\":${escJsonSticker187(c.base)}}"

internal val STICKER_PINNED187 = listOf(
    // Scheme edges.
    StickerCase187("https://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("HTTP://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("Http://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("http://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("ftp://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("http+unix://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("a://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("a+b-c.d://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("ws://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("mailto:foo@bar", STICKER_DEFAULT_BASE187),
    StickerCase187("a:b", STICKER_DEFAULT_BASE187),
    StickerCase187("\u00e9://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("h\u00e9tps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("1http://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("http ://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https:foo", STICKER_DEFAULT_BASE187),
    StickerCase187("https:/a", STICKER_DEFAULT_BASE187),
    StickerCase187("https:", STICKER_DEFAULT_BASE187),
    StickerCase187("https:?", STICKER_DEFAULT_BASE187),
    StickerCase187("https:#g", STICKER_DEFAULT_BASE187),
    StickerCase187("https:foo?y=2", STICKER_DEFAULT_BASE187),
    StickerCase187("https:chat.example.com/a", STICKER_DEFAULT_BASE187),
    StickerCase187("https:chat.example.com/a", "https://chat.example.com/api/v1/stickers/a.webp"),
    StickerCase187("", STICKER_DEFAULT_BASE187),
    StickerCase187(" ", STICKER_DEFAULT_BASE187),
    StickerCase187("?", STICKER_DEFAULT_BASE187),
    StickerCase187("?y=2", STICKER_DEFAULT_BASE187),
    StickerCase187("#", STICKER_DEFAULT_BASE187),
    StickerCase187("#g", STICKER_DEFAULT_BASE187),
    StickerCase187("//", STICKER_DEFAULT_BASE187),
    StickerCase187("///", STICKER_DEFAULT_BASE187),
    // Authority / userinfo / port.
    StickerCase187("//user@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:pw@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:pa:ss@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\t@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\n@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:\t@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%09@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user@name@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user;x:pass@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:pa;ss@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:pa/ss@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:pa?ss@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//user:pa#ss@chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:80/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:443/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:8443/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:0/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:65535/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:65536/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:+80/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:080/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:00000000000000000080/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:2147483647/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:2147483648/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:99999999999999999999/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com: 80/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:80 /a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com:80:90/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https:///a", STICKER_DEFAULT_BASE187),
    StickerCase187("https://", STICKER_DEFAULT_BASE187),
    StickerCase187("///a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("////chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https:\\\\chat.example.com\\a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com\\a.webp", STICKER_DEFAULT_BASE187),
    // Host: IDN, dots, IPv4-looking, raw ASCII.
    StickerCase187("//caf\u00e9.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\u4e2d\u6587/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\u2603/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\u00e9/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\uD83D\uDE00/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\uFFFD/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\u200bb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//\u200b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\u2000b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\u00a0b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\u3000b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff0fb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff1ab/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff20b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff03b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff1fb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff05b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff3bb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff0eb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff0db/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff3fb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\uff0cb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\u3001b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\u30fbb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//ho st/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a^b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a|b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a,b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a;b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a=b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a!b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a~b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a`b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a{b}/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a'b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\"b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a\$b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a&b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a(b)/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a*b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a+b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a]b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a[b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a_b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//-/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//./a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//.a/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a../a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a./a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a..b/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//999.1.1.1/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//1.2.3.400/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//01.2.3.4/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//0x7f.1/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a%2eb/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a%2e%2e/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%2e/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%2e%2e/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//a%2e/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%2ea/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//x%2ey/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%e9/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%ed%a0%80/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%c3%28/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%ff/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%00/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%01/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%c3%a9/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//%63hat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    // Host: IPv6.
    StickerCase187("//[::1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[2001:0DB8:0:0:0:0:0:1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:192.168.0.1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:c0a8:1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1:2:3:4:5:6:1.2.3.4]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1.2.3.4]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1.2.3.4]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:1.2.3.256]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:01.2.3.4]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:1.2.3]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:1.2.3.4.5]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:0:0]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[0:0:0:0:0:ffff:0:0]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::ffff:0:192.168.0.1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[fe80::1%25eth0]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[fe80::1%eth0]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[v7.x]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1]extra/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1]:80/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1]:0/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1]:65536/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[::1]:080/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[%3A%3A1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[%5B::1%5D]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1:2:3:4:5:6:7:8:9]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1:2:3:4:5:6:7]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1::2::3]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[:1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1:]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[2001:db8:0:0:1:0:0:1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[2001:db8:0:0:0:0:2:1]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1:0:0:2:0:0:0:3]/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("//[1:0:0:2:3:0:0:4]/a.webp", STICKER_DEFAULT_BASE187),
    // Path: dot segments, %2e, encode set.
    StickerCase187("/../a", STICKER_DEFAULT_BASE187),
    StickerCase187("/../../a", STICKER_DEFAULT_BASE187),
    StickerCase187("/./a", STICKER_DEFAULT_BASE187),
    StickerCase187("/a/../../b", STICKER_DEFAULT_BASE187),
    StickerCase187("/a/./b", STICKER_DEFAULT_BASE187),
    StickerCase187("/a/b/../../../c", STICKER_DEFAULT_BASE187),
    StickerCase187("/..", STICKER_DEFAULT_BASE187),
    StickerCase187("/.", STICKER_DEFAULT_BASE187),
    StickerCase187("/../", STICKER_DEFAULT_BASE187),
    StickerCase187("/./", STICKER_DEFAULT_BASE187),
    StickerCase187("a/../../b", STICKER_DEFAULT_BASE187),
    StickerCase187("../a", STICKER_DEFAULT_BASE187),
    StickerCase187("./a", STICKER_DEFAULT_BASE187),
    StickerCase187("a/..", STICKER_DEFAULT_BASE187),
    StickerCase187("a/.", STICKER_DEFAULT_BASE187),
    StickerCase187("..", STICKER_DEFAULT_BASE187),
    StickerCase187(".", STICKER_DEFAULT_BASE187),
    StickerCase187("../", STICKER_DEFAULT_BASE187),
    StickerCase187("./", STICKER_DEFAULT_BASE187),
    StickerCase187("a/../", STICKER_DEFAULT_BASE187),
    StickerCase187("a/./", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%2eb/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%2e/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/%2e%2e/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/x%252e/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/%2E/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/%2e./c", STICKER_DEFAULT_BASE187),
    StickerCase187("/.%2E/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/%2e%2E/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%2Fb/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%2fb/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%25/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%2/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%zz/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a%2g/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\"b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a'b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a<b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a>b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a^b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a`b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a{b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a|b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a}b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a!b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\$b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a&b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a(b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a)b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a*b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a+b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a,b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a;b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a=b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a@b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a[b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a]b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a_b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a~b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a:b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.c/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\u0085b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\u00a0b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\u200bb/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\ufeffb/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\uD83D\uDE00b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\u0301b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\u0001b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\u007fb/c", STICKER_DEFAULT_BASE187),
    // Query / fragment.
    StickerCase187("/a.webp?x=^`{|}", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\"'<>", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=caf\u00e9", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\uD83D\uDE00", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\u0001", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\u007f", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\u0085", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=a/b\\c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=a%2fb", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=a%zz", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=a+b", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=a;b=c&d=e", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\uD83D\uDE00", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#caf\u00e9", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\u0001x", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\u007fx", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\u0085x", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\u000bx", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\u000cx", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\ud800", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\ud800\udc00", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\udc00\ud800", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp# ", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#\"<>", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#%^", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp#{}|", STICKER_DEFAULT_BASE187),
    // Lone surrogates in path / query.
    StickerCase187("/a\ud800b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\udc00b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a\udc00\ud800b/c", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\ud800", STICKER_DEFAULT_BASE187),
    StickerCase187("/a.webp?x=\ud800\udc00", STICKER_DEFAULT_BASE187),
    // Trim edges.
    StickerCase187("\u0001https://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u007fhttps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u0085https://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u200bhttps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\ufeffhttps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u00a0https://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u3000https://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u000bhttps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u000chttps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("\u001fhttps://chat.example.com/a.webp", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u0001", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u007f", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u0085", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u00a0", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u3000", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u000b", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u000c", STICKER_DEFAULT_BASE187),
    StickerCase187("https://chat.example.com/a.webp\u001f", STICKER_DEFAULT_BASE187),
    // Base shapes.
    StickerCase187("b.webp", "https://chat.example.com"),
    StickerCase187("/b.webp", "https://chat.example.com"),
    StickerCase187("sub/b.webp", "https://chat.example.com"),
    StickerCase187("../b.webp", "https://chat.example.com"),
    StickerCase187("b.webp", "https://chat.example.com/a"),
    StickerCase187("b.webp", "https://chat.example.com/a/"),
    StickerCase187("b.webp", "https://chat.example.com//a"),
    StickerCase187("../b.webp", "https://chat.example.com//a"),
    StickerCase187("b.webp", "https://chat.example.com/a//"),
    StickerCase187("../b.webp", "https://chat.example.com/a//"),
    StickerCase187("b.webp", "https://chat.example.com/a/b/"),
    StickerCase187("../b.webp", "https://chat.example.com/a/b/"),
    StickerCase187("b.webp", "http://chat.example.com:80/a"),
    StickerCase187("b.webp", "http://chat.example.com:8080/a"),
    StickerCase187("b.webp", "https://user@chat.example.com/a"),
    StickerCase187("b.webp", "https://user:pw@chat.example.com/a"),
    StickerCase187("b.webp", "https://chat.example.com/a?x=1"),
    StickerCase187("b.webp", "https://chat.example.com/a#f"),
    StickerCase187("b.webp", "https://chat.example.com/a?x=1#f"),
    StickerCase187("b.webp", "https://[::1]/a"),
    StickerCase187("b.webp", "https://[::1]:8443/a"),
    StickerCase187("b.webp", "https://chat.example.com./a"),
    StickerCase187("b.webp", "https://./a"),
    StickerCase187("https://other.example.com/a.webp", "https://chat.example.com/a"),
    StickerCase187("https://chat.example.com/a.webp", "ftp://chat.example.com/a"),
    StickerCase187("b.webp", "ftp://chat.example.com/a"),
    StickerCase187("b.webp", "https://"),
    StickerCase187("b.webp", "https://chat.example.com:0/a"),
    StickerCase187("b.webp", "chat.example.com/a"),
    StickerCase187("b.webp", ""),
    StickerCase187("b.webp", "   ")
)

class StickerSourcePolicyFuzzTest {

    private fun checkSticker187(c: StickerCase187) {
        val oracle = stickerResolveOracle187(c.raw, c.base)
        val production = stickerResolveProduction187(c.raw, c.base)
        assertEquals(
            stickerResultWireJson187(oracle),
            stickerResultWireJson187(production),
            "sticker(raw=${c.raw}, base=${c.base})"
        )
    }

    @Test
    fun pinnedStickerSource() {
        for (c in STICKER_PINNED187) checkSticker187(c)
    }

    @Test
    fun randomStickerSourceMatchProduction() {
        val rnd = Random(187001)
        repeat(2500) { checkSticker187(randomStickerCase187(rnd)) }
    }

    @Test
    fun sweepStickerSourceBoundaries() {
        // Every scheme x every host shape, absolute.
        for (scheme in listOf("https", "http", "HTTPS", "ftp", "ws")) {
            for (host in listOf(
                "chat.example.com", "other.example.com", "[::1]",
                "chat.example.com:8443", "caf\u00e9.example.com", "."
            )) {
                checkSticker187(StickerCase187("$scheme://$host/a.webp", STICKER_DEFAULT_BASE187))
            }
        }
        // Every base x a small relative set.
        for (base in STICKER_BASES187) {
            for (raw in listOf("b.webp", "/b.webp", "../b.webp", ".", "..", "", "?", "#f", "https:", "https:/a")) {
                checkSticker187(StickerCase187(raw, base))
            }
        }
        // Port neighbours on the default base host.
        for (port in listOf("0", "1", "79", "80", "81", "442", "443", "444", "65534", "65535", "65536", "+443", "0443", "")) {
            checkSticker187(StickerCase187("https://chat.example.com:$port/a.webp", STICKER_DEFAULT_BASE187))
        }
        // Dot-segment words at each position of a 3-segment path.
        for (seg in listOf(".", "..", "%2e", "%2E", ".%2e", "%2e.", "%2e%2e", "%252e", "a%2e", "%2e%2e%2e")) {
            checkSticker187(StickerCase187("/a/$seg/b", STICKER_DEFAULT_BASE187))
            checkSticker187(StickerCase187("/$seg/a/b", STICKER_DEFAULT_BASE187))
            checkSticker187(StickerCase187("/a/b/$seg", STICKER_DEFAULT_BASE187))
        }
        // Userinfo edge matrix.
        for (ui in listOf("", "user", "user:pw", "user:", ":pw", ":", "@", "user@", "@user")) {
            checkSticker187(StickerCase187("//$ui@chat.example.com/a.webp", STICKER_DEFAULT_BASE187))
        }
    }
}
