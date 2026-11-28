// Fuzz / random-oracle tests for AttachmentTransferError (checklist line 931,
// 98th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. AttachmentTransferErrorPolicy: error codes (ApiException KIND_status, else the simple class name), retryability, and claim-failure disposition.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentTransferErrorFuzzTest {

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

// ---------- block 98 shared ----------

// Error descriptor: one input type for every cluster that takes a
// Throwable. tag: N = null, IO = IOException, OT = RuntimeException,
// AN = anonymous object : Exception (empty simple name), AR =
// IllegalArgumentException, ST = IllegalStateException, NP =
// NullPointerException, SE = SecurityException, AP = ApiException
// (kind/status/code/message/retryAfter). Only the feedback cluster
// ever uses N (fromThrowable takes Throwable?); the transfer-error
// cluster uses genThrowableDesc98, which never yields N.
// Nullable wire codec, self-contained (the test files compile
// without Parse98.kt, so these cannot reuse its helpers; the 98
// suffix avoids colliding with Parse98.kt in Oracle98/Probe98).
private fun encStr98(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun decStr98(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4)
    for (i in chars.indices) {
        chars[i] = h.substring(i * 4, i * 4 + 4).toInt(16).toChar()
    }
    return String(chars)
}

private fun encNullableStr98(s: String?): String = if (s == null) "N" else "S" + encStr98(s)

private fun decNullableStr98(f: String): String? = if (f == "N") null else decStr98(f.substring(1))

private fun encNullableInt98(v: Int?): String = if (v == null) "N" else "S" + v.toString()

private fun decNullableInt98(f: String): Int? = if (f == "N") null else f.substring(1).toInt()

private fun encNullableLong98(v: Long?): String = if (v == null) "N" else "S" + v.toString()

private fun decNullableLong98(f: String): Long? = if (f == "N") null else f.substring(1).toLong()

data class ErrorDesc98(
    val tag: String,
    val kind: String?,
    val status: Int?,
    val code: String?,
    val message: String?,
    val retryAfter: Long?,
)

private val ERROR_TAG_POOL98 = arrayOf("N", "IO", "OT", "AN", "AR", "ST", "NP", "SE", "AP")

private val ERROR_CLASS_TAG_POOL98 = arrayOf("IO", "OT", "AN", "AR", "ST", "NP", "SE", "AP")

private val API_KIND_POOL98 = arrayOf("HTTP", "TIMEOUT", "NETWORK", "INVALID_RESPONSE", "UNEXPECTED")

private val API_STATUS_POOL98: Array<Int?> = arrayOf(
    null, 200, 400, 401, 403, 404, 409, 410, 429, 499, 500, 503,
)

private val API_CODE_POOL98: Array<String?> = arrayOf(
    null, "", " ", "   ",
    "GROUP_PERMISSION_DENIED", "GROUP_ACTOR_NOT_MEMBER",
    "GROUP_OWNER_PROTECTED", "GROUP_ADMIN_PEER_PROTECTED",
    "GROUP_MEMBER_BLOCKED",
    "GROUP_NOT_FOUND", "GROUP_TARGET_NOT_MEMBER",
    "GROUP_USER_NOT_FOUND", "GROUP_MEMBER_LIMIT_EXCEEDED",
    "GROUP_OWNER_TRANSFER_REQUIRED",
    "FOO", "NOT_GROUP_OWNER",
    " GROUP_PERMISSION_DENIED ", "GROUP_PERMISSION_DENIED ",
)

private val ERROR_MSG_POOL98: Array<String?> = arrayOf(
    null, "", " ", "  ", "msg", "  msg  ", "offline", "busy", "already",
    "只有群主可以转让群主身份", "禁言最长不能超过 30 天",
)

private val RETRY_AFTER_POOL98: Array<Long?> = arrayOf(null, 0L, 30L, 60L)

private fun genErrorDesc98(rnd: Random): ErrorDesc98 {
    val tag = when (rnd.nextInt(10)) {
        0 -> "N"
        1, 2 -> "IO"
        3, 4, 5, 6 -> "AP"
        7 -> "OT"
        8 -> "AN"
        else -> arrayOf("AR", "ST", "NP", "SE")[rnd.nextInt(4)]
    }
    return errorDescForTag98(rnd, tag)
}

private fun genThrowableDesc98(rnd: Random): ErrorDesc98 {
    val tag = ERROR_CLASS_TAG_POOL98[rnd.nextInt(ERROR_CLASS_TAG_POOL98.size)]
    return errorDescForTag98(rnd, tag)
}

private fun errorDescForTag98(rnd: Random, tag: String): ErrorDesc98 {
    if (tag == "AP") {
        return ErrorDesc98(
            tag = tag,
            kind = API_KIND_POOL98[rnd.nextInt(API_KIND_POOL98.size)],
            status = API_STATUS_POOL98[rnd.nextInt(API_STATUS_POOL98.size)],
            code = API_CODE_POOL98[rnd.nextInt(API_CODE_POOL98.size)],
            message = ERROR_MSG_POOL98[rnd.nextInt(ERROR_MSG_POOL98.size)],
            retryAfter = RETRY_AFTER_POOL98[rnd.nextInt(RETRY_AFTER_POOL98.size)],
        )
    }
    if (tag == "N") {
        return ErrorDesc98(tag, null, null, null, null, null)
    }
    return ErrorDesc98(
        tag = tag,
        kind = null,
        status = null,
        code = null,
        message = ERROR_MSG_POOL98[rnd.nextInt(ERROR_MSG_POOL98.size)],
        retryAfter = null,
    )
}

private fun genErrorDescEdge98(rnd: Random): ErrorDesc98 {
    val tag = ERROR_TAG_POOL98[rnd.nextInt(ERROR_TAG_POOL98.size)]
    return errorDescEdgeForTag98(rnd, tag)
}

private fun genThrowableDescEdge98(rnd: Random): ErrorDesc98 {
    val tag = ERROR_CLASS_TAG_POOL98[rnd.nextInt(ERROR_CLASS_TAG_POOL98.size)]
    return errorDescEdgeForTag98(rnd, tag)
}

private fun errorDescEdgeForTag98(rnd: Random, tag: String): ErrorDesc98 {
    if (tag == "AP") {
        return ErrorDesc98(
            tag = tag,
            kind = API_KIND_POOL98[rnd.nextInt(API_KIND_POOL98.size)],
            status = API_STATUS_POOL98[rnd.nextInt(API_STATUS_POOL98.size)],
            code = API_CODE_POOL98[rnd.nextInt(API_CODE_POOL98.size)],
            message = ERROR_MSG_POOL98[rnd.nextInt(ERROR_MSG_POOL98.size)],
            retryAfter = RETRY_AFTER_POOL98[rnd.nextInt(RETRY_AFTER_POOL98.size)],
        )
    }
    if (tag == "N") {
        return ErrorDesc98(tag, null, null, null, null, null)
    }
    return ErrorDesc98(
        tag = tag,
        kind = null,
        status = null,
        code = null,
        message = ERROR_MSG_POOL98[rnd.nextInt(ERROR_MSG_POOL98.size)],
        retryAfter = null,
    )
}

// Wire form: tag + "|" + kind + "|" + status + "|" + code + "|" +
// message + "|" + retryAfter (kinds as names, nullable ints/longs
// as N / S+decimal, nullable strings as N / S+hex).
private fun encErrorDesc98(d: ErrorDesc98): String =
    d.tag + "|" + encNullableStr98(d.kind) + "|" + encNullableInt98(d.status) + "|" +
        encNullableStr98(d.code) + "|" + encNullableStr98(d.message) + "|" +
        encNullableLong98(d.retryAfter)

private fun decErrorDesc98(f: String): ErrorDesc98 {
    val parts = f.split("|")
    return ErrorDesc98(
        tag = parts[0],
        kind = decNullableStr98(parts[1]),
        status = decNullableInt98(parts[2]),
        code = decNullableStr98(parts[3]),
        message = decNullableStr98(parts[4]),
        retryAfter = decNullableLong98(parts[5]),
    )
}

// Build the real Throwable a descriptor stands for (probe + tests).
// ApiException's message is its serverMessage, exactly as the
// production constructors do.
private fun throwableFromDesc98(d: ErrorDesc98): Throwable? {
    return when (d.tag) {
        "N" -> null
        "IO" -> java.io.IOException(d.message)
        "OT" -> RuntimeException(d.message)
        "AN" -> object : Exception(d.message) {}
        "AR" -> IllegalArgumentException(d.message)
        "ST" -> IllegalStateException(d.message)
        "NP" -> NullPointerException(d.message)
        "SE" -> SecurityException(d.message)
        "AP" -> com.maodouchat.core.model.ApiException(
            kind = apiFailureKindFromName98(d.kind ?: "UNEXPECTED"),
            statusCode = d.status,
            serverMessage = d.message,
            serverCode = d.code,
            retryAfterSeconds = d.retryAfter,
        )
        else -> error("unknown error tag " + d.tag)
    }
}

private fun apiFailureKindFromName98(name: String): com.maodouchat.core.model.ApiFailureKind =
    when (name) {
        "HTTP" -> com.maodouchat.core.model.ApiFailureKind.HTTP
        "TIMEOUT" -> com.maodouchat.core.model.ApiFailureKind.TIMEOUT
        "NETWORK" -> com.maodouchat.core.model.ApiFailureKind.NETWORK
        "INVALID_RESPONSE" -> com.maodouchat.core.model.ApiFailureKind.INVALID_RESPONSE
        "UNEXPECTED" -> com.maodouchat.core.model.ApiFailureKind.UNEXPECTED
        else -> error("unknown ApiFailureKind " + name)
    }

// ---------- AttachmentTransferError oracle ----------

// Hand-written reimplementation of
// AttachmentTransferErrorPolicy.transferErrorCode /
// isRetryableTransferError / claimFailureDisposition. The error
// code for an ApiException is "KIND_status" (null status is 0);
// any other error is its simple class name, taken to 80 units,
// blank -> "UNKNOWN" (anonymous classes have no simple name).
// Retryable iff retryAfter != null, NETWORK/TIMEOUT, 429, or
// status >= 500 -- else iff IOException. The claim disposition
// is RETRY only for QUEUED/UPLOADING/FAILED.
private fun errorCodeOracle(desc: ErrorDesc98): String {
    val raw = when (desc.tag) {
        "AP" -> (desc.kind ?: "UNEXPECTED") + "_" + (desc.status ?: 0).toString()
        "IO" -> "IOException"
        "OT" -> "RuntimeException"
        "AN" -> ""
        "AR" -> "IllegalArgumentException"
        "ST" -> "IllegalStateException"
        "NP" -> "NullPointerException"
        "SE" -> "SecurityException"
        else -> error("errorCodeOracle: no throwable for tag " + desc.tag)
    }
    val taken = takeOracle(raw, 80)
    return if (isBlankOracle(taken)) "UNKNOWN" else taken
}

private fun retryableOracle(desc: ErrorDesc98): Boolean =
    when (desc.tag) {
        "AP" -> desc.retryAfter != null ||
            desc.kind == "NETWORK" || desc.kind == "TIMEOUT" ||
            desc.status == 429 || (desc.status ?: 0) >= 500
        "IO" -> true
        else -> false
    }

private fun claimDispositionOracle(state: String?): AttachmentTransferDisposition =
    when (state) {
        "QUEUED", "UPLOADING", "FAILED" -> AttachmentTransferDisposition.RETRY
        else -> AttachmentTransferDisposition.SUCCESS
    }

private val CLAIM_STATE_POOL98: Array<String?> = arrayOf(
    "QUEUED", "UPLOADING", "FAILED", "PREPARING", null, "",
    "COMMITTED", "CANCELLED", "READY", "QUEUED ", "queued", "FAILED ",
)

private fun genClaimState(rnd: Random): String? =
    CLAIM_STATE_POOL98[rnd.nextInt(CLAIM_STATE_POOL98.size)]

    @Test
    fun errorRandom() {
        val rnd = Random(42)
        repeat(300) {
            val desc = genThrowableDesc98(rnd)
            val error = throwableFromDesc98(desc)!!
            val state = genClaimState(rnd)
            assertEquals(
                errorCodeOracle(desc),
                AttachmentTransferErrorPolicy.transferErrorCode(error)
            )
            assertEquals(
                retryableOracle(desc),
                AttachmentTransferErrorPolicy.isRetryableTransferError(error)
            )
            assertEquals(
                claimDispositionOracle(state),
                AttachmentTransferErrorPolicy.claimFailureDisposition(state)
            )
        }
    }

    @Test
    fun errorEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The edge leg cycles the throwable pools: every
            // ApiFailureKind against every status and retryAfter,
            // plus every plain exception class.
            val desc = genThrowableDescEdge98(rnd)
            val error = throwableFromDesc98(desc)!!
            val state = genClaimState(rnd)
            assertEquals(
                errorCodeOracle(desc),
                AttachmentTransferErrorPolicy.transferErrorCode(error)
            )
            assertEquals(
                retryableOracle(desc),
                AttachmentTransferErrorPolicy.isRetryableTransferError(error)
            )
            assertEquals(
                claimDispositionOracle(state),
                AttachmentTransferErrorPolicy.claimFailureDisposition(state)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // ApiException codes are KIND_status, null status is 0.
        assertEquals(
            "HTTP_500",
            AttachmentTransferErrorPolicy.transferErrorCode(
                com.maodouchat.core.model.ApiException(
                    kind = com.maodouchat.core.model.ApiFailureKind.HTTP,
                    statusCode = 500,
                )
            )
        )
        assertEquals(
            "NETWORK_0",
            AttachmentTransferErrorPolicy.transferErrorCode(
                com.maodouchat.core.model.ApiException(
                    kind = com.maodouchat.core.model.ApiFailureKind.NETWORK,
                )
            )
        )
        // Plain errors are their simple class names; an anonymous
        // class has none and falls back to UNKNOWN.
        assertEquals(
            "RuntimeException",
            AttachmentTransferErrorPolicy.transferErrorCode(RuntimeException("boom"))
        )
        assertEquals(
            "IOException",
            AttachmentTransferErrorPolicy.transferErrorCode(java.io.IOException("eof"))
        )
        assertEquals(
            "UNKNOWN",
            AttachmentTransferErrorPolicy.transferErrorCode(object : Exception() {})
        )
        // Retry-after makes an otherwise plain API error retryable.
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                com.maodouchat.core.model.ApiException(
                    kind = com.maodouchat.core.model.ApiFailureKind.HTTP,
                    statusCode = 404,
                    retryAfterSeconds = 30,
                )
            )
        )
        // 429 is retryable, 404 without retry-after is not, 500 is.
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                com.maodouchat.core.model.ApiException(
                    kind = com.maodouchat.core.model.ApiFailureKind.HTTP,
                    statusCode = 429,
                )
            )
        )
        assertEquals(
            false,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                com.maodouchat.core.model.ApiException(
                    kind = com.maodouchat.core.model.ApiFailureKind.HTTP,
                    statusCode = 404,
                )
            )
        )
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(
                com.maodouchat.core.model.ApiException(
                    kind = com.maodouchat.core.model.ApiFailureKind.HTTP,
                    statusCode = 500,
                )
            )
        )
        // A plain IOException is retryable, a plain
        // RuntimeException is not.
        assertEquals(
            true,
            AttachmentTransferErrorPolicy.isRetryableTransferError(java.io.IOException("eof"))
        )
        assertEquals(
            false,
            AttachmentTransferErrorPolicy.isRetryableTransferError(RuntimeException("boom"))
        )
        // Claim disposition: only the three pending states retry.
        assertEquals(
            AttachmentTransferDisposition.RETRY,
            AttachmentTransferErrorPolicy.claimFailureDisposition("QUEUED")
        )
        assertEquals(
            AttachmentTransferDisposition.RETRY,
            AttachmentTransferErrorPolicy.claimFailureDisposition("FAILED")
        )
        assertEquals(
            AttachmentTransferDisposition.SUCCESS,
            AttachmentTransferErrorPolicy.claimFailureDisposition("PREPARING")
        )
        assertEquals(
            AttachmentTransferDisposition.SUCCESS,
            AttachmentTransferErrorPolicy.claimFailureDisposition(null)
        )
        assertEquals(
            AttachmentTransferDisposition.SUCCESS,
            AttachmentTransferErrorPolicy.claimFailureDisposition("queued")
        )
    }
}
