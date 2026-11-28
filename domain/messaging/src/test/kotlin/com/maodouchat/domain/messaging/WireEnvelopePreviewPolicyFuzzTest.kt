// Fuzz / random-oracle tests for WireEnvelopePreviewPolicy (checklist line 931,
// 112th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. WireEnvelopePreviewPolicy: wire-envelope detection, redaction, own-preview keep, plain/encrypted preview.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.MessageType

class WireEnvelopePreviewPolicyFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun indexOfCharOracle(s: String, ch: Char): Int {
    for (i in s.indices) {
        if (s[i] == ch) return i
    }
    return -1
}

// Java String.hashCode(): h = 31*h + unit, Int arithmetic wraps.
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// java.lang.Long.parseLong(s, radix), reimplemented with the same
// JDK primitive (Character.digit) and the same negative-accumulator
// overflow algorithm, so the exact Long.MIN_VALUE edge parses.
private fun parseLongRadixOracle(s: String, radix: Int): Long? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0L
    val limit = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
    val multmin = limit / radix
    while (i < s.length) {
        val d = Character.digit(s[i].code, radix)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= radix
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

// java.lang.Integer.parseInt(s), same algorithm on Int.
private fun parseIntOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0
    val limit = if (neg) Int.MIN_VALUE else -Int.MAX_VALUE
    val multmin = limit / 10
    while (i < s.length) {
        val d = Character.digit(s[i].code, 10)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= 10
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

private fun isDigitOracle(c: Char): Boolean = Character.isDigit(c.code)


// ---------- extra helpers, block 80 ----------

// Per-code-point simple case mapping, mirroring lowercaseOracle.
// Agrees with String.uppercase() (full mapping) on the case-safe
// alphabet; never calls uppercase().
private fun uppercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toUpperCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// String.substringBefore(delimiter): up to the first occurrence,
// or the whole string when absent.
private fun substringBeforeOracle(s: String, delim: Char): String {
    val idx = indexOfCharOracle(s, delim)
    return if (idx < 0) s else s.substring(0, idx)
}

// ---------- block-112 common oracle helpers ----------

// (No shared production-type helpers: each cluster's oracle below
// is self-contained. The wire codecs live in Parse112.kt.)

private fun trimStartOracle(s: String): String {
    var lo = 0
    while (lo < s.length && s[lo].code in BLANK_UNITS_ORACLE) lo++
    return s.substring(lo)
}

private fun indexOfStrOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var i = 0
    while (i + needle.length <= s.length) {
        var ok = true
        for (j in needle.indices) if (s[i + j] != needle[j]) { ok = false; break }
        if (ok) return i
        i++
    }
    return -1
}

private fun lastIndexOfStrOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return s.length
    var i = s.length - needle.length
    while (i >= 0) {
        var ok = true
        for (j in needle.indices) if (s[i + j] != needle[j]) { ok = false; break }
        if (ok) return i
        i--
    }
    return -1
}

private fun substringBeforeStrOracle(s: String, needle: String): String {
    val idx = indexOfStrOracle(s, needle)
    return if (idx < 0) s else s.substring(0, idx)
}

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- WireEnvelopePreviewPolicy oracle ----------

// Hand-written reimplementation of the wire-envelope preview
// helpers. visiblePreviewText strips a trailing "<meta>" block,
// trims, hides local media URIs and unescapes HTML entities;
// isSignalWireEnvelope needs a JSON shape plus a Signal feature;
// looksLikeLeftoverPreviewGarbage hides decrypt placeholders,
// bare cipher dumps and leftover attachment/join URLs.


private fun unescapeHtmlEntitiesOracle(raw: String): String {
    if (raw.isEmpty() || indexOfCharOracle(raw, '&') < 0) return raw
    var out = raw
    out = Regex("&#(\\d{1,7});").replace(out) { match ->
        val code = parseLongRadixOracle(match.groupValues[1], 10)
        if (code != null && code in 1..0x10FFFF) String(intArrayOf(code.toInt()), 0, 1) else match.value
    }
    out = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE).replace(out) { match ->
        val code = parseLongRadixOracle(match.groupValues[1], 16)
        if (code != null && code in 1..0x10FFFF) String(intArrayOf(code.toInt()), 0, 1) else match.value
    }
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

private fun isRemoteAttachmentUriOracle(value: String): Boolean =
    startsWithOracle(value, "maodou-attachment://")

private fun looksLikeLocalMediaUriOracle(content: String): Boolean {
    val t = trimOracle(content)
    return startsWithOracle(t, "file:") ||
        startsWithOracle(t, "content:") ||
        isRemoteAttachmentUriOracle(t)
}

private fun visiblePreviewTextOracle(raw: String): String {
    val idx = lastIndexOfStrOracle(raw, "<meta>")
    val withoutMeta = trimOracle(if (idx < 0) raw else raw.substring(0, idx))
    if (isBlankOracle(withoutMeta)) return ""
    if (looksLikeLocalMediaUriOracle(withoutMeta)) return ""
    return unescapeHtmlEntitiesOracle(withoutMeta)
}

private fun isSignalWireEnvelopeOracle(content: String): Boolean {
    val t = trimStartOracle(content)
    if (!startsWithOracle(t, "{") && !startsWithOracle(t, "[{")) return false
    if (containsOracle(t, "signal-")) {
        return containsOracle(t, "\"algorithm\"") || containsOracle(t, "\"distributionMessage\"") ||
            containsOracle(t, "\"ciphertext\"") || containsOracle(t, "\"senderKey")
    }
    val hasCiphertext = containsOracle(t, "\"ciphertext\"")
    val hasSenderDevice = containsOracle(t, "\"senderDeviceId\"")
    val hasEntries = containsOracle(t, "\"entries\"")
    val hasCiphertextType = containsOracle(t, "\"ciphertextType\"")
    val hasDistribution = containsOracle(t, "\"distributionMessage\"")
    return hasDistribution ||
        (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
}

private val LEGACY_PLACEHOLDERS_ORACLE = setOf(
    "无法解密", "解密失败", "群密钥缺失", "密钥缺失",
    "decrypt failed", "session missing", "identity changed",
    "[无法解密的消息]", "[无法解密的群聊消息]", "[加密消息]",
    "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]",
    "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]",
    "[群聊密钥 epoch 较新，请刷新群信息]",
    "[无法解密的图片]", "[无法解密的 gif]", "[无法解密的贴纸]",
    "[无法解密的位置]", "[无法解密的视频]", "[无法解密的语音]",
    "[无法解密的文件]",
    "[unable to decrypt message]", "[unable to decrypt group message]",
    "[encrypted message]",
    "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group key epoch. refresh group info]",
    "[unable to decrypt image]", "[unable to decrypt gif]",
    "[unable to decrypt sticker]", "[unable to decrypt location]",
    "[unable to decrypt video]", "[unable to decrypt voice message]",
    "[unable to decrypt file]"
)

private fun looksLikeDecryptFailurePlaceholderOracle(content: String): Boolean {
    val normalized = lowercaseOracle(trimOracle(content))
    if (normalized.isEmpty()) return false
    return normalized in LEGACY_PLACEHOLDERS_ORACLE
}

private fun isUrlOnlyOracle(text: String): Boolean {
    val t = trimOracle(text)
    if (t.isEmpty() || containsOracle(t, " ") || containsOracle(t, "\n")) return false
    val lower = lowercaseOracle(t)
    return startsWithOracle(lower, "http://") ||
        startsWithOracle(lower, "https://") ||
        startsWithOracle(lower, "www.")
}

private fun looksLikeLeftoverCipherOrJoinUrlOracle(url: String): Boolean {
    val lower = lowercaseOracle(url)
    return containsOracle(lower, "/api/attachments") ||
        containsOracle(lower, "maodou-attachment") ||
        containsOracle(lower, "ciphertext") ||
        containsOracle(lower, "senderdeviceid")
}

private fun looksLikeLeftoverPreviewGarbageOracle(content: String): Boolean {
    val t = trimOracle(content)
    if (isBlankOracle(t)) return true
    if (looksLikeLocalMediaUriOracle(t)) return true
    if (isSignalWireEnvelopeOracle(t)) return true
    if (startsWithOracle(t, "eyJ") && t.length >= 40) return true
    if (containsIgnoreCaseOracle(t, "<meta>") || containsIgnoreCaseOracle(t, "</meta>")) return true
    if (looksLikeDecryptFailurePlaceholderOracle(t)) return true
    if (isUrlOnlyOracle(t) && looksLikeLeftoverCipherOrJoinUrlOracle(t)) return true
    return false
}

private fun looksLikeWireEnvelopeOracle(content: String): Boolean {
    val t = trimStartOracle(content)
    if (startsWithOracle(t, "{")) return true
    if (startsWithOracle(t, "[{") || startsWithOracle(t, "[\"")) return true
    return false
}

private fun redactedIfWireOracle(content: String, placeholder: String): String {
    val body = visiblePreviewTextOracle(content)
    if (isBlankOracle(body)) return placeholder
    return if (isSignalWireEnvelopeOracle(content) || isSignalWireEnvelopeOracle(body)) placeholder else body
}

private fun shouldKeepExistingOwnPreviewOracle(
    isOwnMessage: Boolean,
    messageType: MessageType,
    existingSameMessageContent: String?,
    encryptedPlaceholder: String
): Boolean {
    if (!isOwnMessage) return false
    if (messageType !in setOf(
            MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER,
            MessageType.LOCATION, MessageType.IMAGE, MessageType.GIF,
            MessageType.VOICE, MessageType.VIDEO, MessageType.FILE,
            MessageType.NUDGE
        )
    ) return false
    val body = existingSameMessageContent?.takeIf { !isBlankOracle(it) } ?: return false
    if (looksLikeWireEnvelopeOracle(body)) return false
    if (looksLikeLeftoverPreviewGarbageOracle(body)) return false
    if (body == encryptedPlaceholder) return false
    return true
}

private fun textPreviewFromPlainOrEncryptedOracle(
    decryptedPlain: String?,
    encryptedPlaceholder: String,
    maxLen: Int = 200
): String {
    val body = decryptedPlain?.takeIf {
        !isBlankOracle(it) &&
            !looksLikeWireEnvelopeOracle(it) &&
            !looksLikeLeftoverPreviewGarbageOracle(it)
    }
    // maxLen is only ever non-negative in the generators (take()
    // throws below zero); takeOracle agrees on n >= 0.
    return if (body == null) encryptedPlaceholder else takeOracle(body, maxLen)
}

private val WEP_CONTENT_POOL112 = arrayOf(
    "", "{}", "{\"a\":1}", " {\"signal-x\":1}", "{\"ciphertext\":\"x\",\"senderDeviceId\":1}",
    "[{\"ciphertext\":\"x\"}]", "[\"x\"]", "[图片]", "[GIF]", "hello",
    "  {\"algorithm\":\"signal\"}",
    "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"abc\"}]}",
    "{\"signal-foo\":1,\"algorithm\":\"x\"}", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0",
    "<meta>tail", "hello<meta>{\"a\":1}", "file:///sdcard/x.jpg",
    "content://media/1", "maodou-attachment://abc",
    "https://example.com/api/attachments/1",
    "https://example.com/share?id=1", "https://example.com/ciphertext/x",
    "无法解密", "[无法解密的消息]", "decrypt failed", "  ",
    "\u00A0{\"a\":1}", "{\"a\":1}\u00A0", "[{\"distributionMessage\":\"x\"}]",
    "{\"ciphertextType\":\"prekey\"}", "{\n\"ciphertext\":\"x\"}",
    "x".repeat(50), "a &amp; b", "&#65;&#x42;", "&#0;",
    "www.example.com/api/attachments/9", "HTTP://EXAMPLE.COM/CIPHERTEXT",
    "eyJshort", "{\u00A0\"ciphertext\":\"x\"}"
)

private val WEP_PLACEHOLDER_POOL112 = arrayOf(
    "[加密消息]", "•••", "", "placeholder", "[无法解密的消息]"
)

private val WEP_MAXLEN_POOL112 = intArrayOf(
    0, 1, 2, 40, 199, 200, 201, 1000
)

private fun genWepContent(rnd: Random): String =
    when (rnd.nextInt(4)) {
        0 -> WEP_CONTENT_POOL112[rnd.nextInt(WEP_CONTENT_POOL112.size)]
        1 -> randomString(rnd, 60)
        else -> genTextOf(rnd, rnd.nextInt(0, 120))
    }

private fun genWepPlaceholder(rnd: Random): String =
    when (rnd.nextInt(4)) {
        0 -> WEP_PLACEHOLDER_POOL112[rnd.nextInt(WEP_PLACEHOLDER_POOL112.size)]
        else -> randomString(rnd, 20)
    }

private fun genWepMaxLen(rnd: Random): Int =
    WEP_MAXLEN_POOL112[rnd.nextInt(WEP_MAXLEN_POOL112.size)]

private fun genWepType(rnd: Random): MessageType =
    MessageType.entries[rnd.nextInt(MessageType.entries.size)]

    @Test
    fun wireEnvelopePreviewPolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val content = genWepContent(rnd)
            assertEquals(
                looksLikeWireEnvelopeOracle(content),
                WireEnvelopePreviewPolicy.looksLikeWireEnvelope(content)
            )
            val placeholder = genWepPlaceholder(rnd)
            assertEquals(
                redactedIfWireOracle(content, placeholder),
                WireEnvelopePreviewPolicy.redactedIfWire(content, placeholder)
            )
            val isOwn = rnd.nextBoolean()
            val type = genWepType(rnd)
            val existing = when (rnd.nextInt(4)) {
                0 -> null
                else -> genWepContent(rnd)
            }
            assertEquals(
                shouldKeepExistingOwnPreviewOracle(isOwn, type, existing, placeholder),
                WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(isOwn, type, existing, placeholder)
            )
            val plain = when (rnd.nextInt(4)) {
                0 -> null
                else -> genWepContent(rnd)
            }
            val maxLen = genWepMaxLen(rnd)
            assertEquals(
                textPreviewFromPlainOrEncryptedOracle(plain, placeholder, maxLen),
                WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted(plain, placeholder, maxLen)
            )
        }
    }

    @Test
    fun wireEnvelopePreviewPolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: envelope shapes and placeholders dominate.
            val content = WEP_CONTENT_POOL112[rnd.nextInt(WEP_CONTENT_POOL112.size)]
            assertEquals(
                looksLikeWireEnvelopeOracle(content),
                WireEnvelopePreviewPolicy.looksLikeWireEnvelope(content)
            )
            val placeholder = WEP_PLACEHOLDER_POOL112[rnd.nextInt(WEP_PLACEHOLDER_POOL112.size)]
            assertEquals(
                redactedIfWireOracle(content, placeholder),
                WireEnvelopePreviewPolicy.redactedIfWire(content, placeholder)
            )
            val isOwn = rnd.nextBoolean()
            val type = genWepType(rnd)
            val existing = when (rnd.nextInt(4)) {
                0 -> null
                else -> WEP_CONTENT_POOL112[rnd.nextInt(WEP_CONTENT_POOL112.size)]
            }
            assertEquals(
                shouldKeepExistingOwnPreviewOracle(isOwn, type, existing, placeholder),
                WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(isOwn, type, existing, placeholder)
            )
            val plain = when (rnd.nextInt(4)) {
                0 -> null
                else -> WEP_CONTENT_POOL112[rnd.nextInt(WEP_CONTENT_POOL112.size)]
            }
            val maxLen = genWepMaxLen(rnd)
            assertEquals(
                textPreviewFromPlainOrEncryptedOracle(plain, placeholder, maxLen),
                WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted(plain, placeholder, maxLen)
            )
        }
    }

    @Test
    fun wireEnvelopePreviewPolicyPinned() {
        assertEquals(true, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("{}"))
        assertEquals(true, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("  {\"a\":1}"))
        assertEquals(true, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[{\"a\":1}]"))
        assertEquals(true, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[\"x\"]"))
        assertEquals(false, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[图片]"))
        assertEquals(false, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("hello"))
        assertEquals(false, WireEnvelopePreviewPolicy.looksLikeWireEnvelope(""))
        assertEquals(false, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("   "))
        assertEquals(true, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[{}"))
        assertEquals(false, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("["))
        assertEquals(true, WireEnvelopePreviewPolicy.looksLikeWireEnvelope("{"))
        assertEquals(
            "[加密]",
            WireEnvelopePreviewPolicy.redactedIfWire("{\"ciphertext\":\"x\",\"senderDeviceId\":1}", "[加密]")
        )
        assertEquals("hello", WireEnvelopePreviewPolicy.redactedIfWire("hello", "[加密]"))
        assertEquals("[加密]", WireEnvelopePreviewPolicy.redactedIfWire("", "[加密]"))
        assertEquals("[加密]", WireEnvelopePreviewPolicy.redactedIfWire("   ", "[加密]"))
        assertEquals("{\"a\":1}", WireEnvelopePreviewPolicy.redactedIfWire("{\"a\":1}", "[加密]"))
        assertEquals("[加密]", WireEnvelopePreviewPolicy.redactedIfWire("file:///x", "[加密]"))
        assertEquals("a & b", WireEnvelopePreviewPolicy.redactedIfWire("a &amp; b", "[加密]"))
        assertEquals(true, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(true, MessageType.TEXT, "hello", "[加密]"))
        assertEquals(false, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(true, MessageType.TEXT, "{\"a\":1}", "[加密]"))
        assertEquals(false, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(true, MessageType.TEXT, "", "[加密]"))
        assertEquals(false, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(false, MessageType.TEXT, "hello", "[加密]"))
        assertEquals(true, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(true, MessageType.IMAGE, "hello", "[加密]"))
        assertEquals(false, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(true, MessageType.SK_DIST, "hello", "[加密]"))
        assertEquals(false, WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(true, MessageType.TEXT, "[加密]", "[加密]"))
        assertEquals("hello", WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("hello", "[加密]"))
        assertEquals("[加密]", WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted(null, "[加密]"))
        assertEquals("[加密]", WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("", "[加密]"))
        assertEquals("[加密]", WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("{\"a\":1}", "[加密]"))
        assertEquals("x".repeat(200), WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("x".repeat(300), "[加密]", 200))
        assertEquals("", WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("x".repeat(300), "[加密]", 0))
        assertEquals("h", WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("hello", "[加密]", 1))
    }
}
