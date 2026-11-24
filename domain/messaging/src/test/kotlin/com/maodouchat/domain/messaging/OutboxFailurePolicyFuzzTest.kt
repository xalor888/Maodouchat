// Fuzz / random-oracle tests for OutboxFailurePolicy (checklist line 931,
// 116th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. shouldDiscardStaleGroupControl / needsFreshEncryption / isSenderKeyCoveragePending.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind

class OutboxFailurePolicyFuzzTest {

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

// ---------- block-116 common oracle helpers ----------

// Per-code-point uppercase (Character.toUpperCase per code point),
// mirroring String.uppercase(): the alphabet audit proves full ==
// simple over the generator alphabet, so no expansion occurs.

// Local hex encoder (same codec as Parse116.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse116.kt, so the codec lives here.
private fun encSpecStr(s: String): String {
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

// ---------- wire value parts (oracle-side, never production) ----------

private data class TargetParts(val userId: String, val deviceId: Int)

private data class CiphertextParts(
    val userId: String,
    val deviceId: Int,
    val ciphertextType: String,
    val ciphertext: String
)

private data class EnvelopeParts(
    val userId: String,
    val deviceId: Int,
    val ciphertextType: String,
    val ciphertext: String
)

private data class EventParts(
    val action: String,
    val targetMessageId: String,
    val status: String,
    val throughMessageId: String?
)

private data class ContentParts(
    val type: String,
    val attributes: List<Pair<String, String>>
)

private data class UscMessageParts(
    val id: String,
    val senderId: String,
    val content: String,
    val type: String,
    val timestamp: Long,
    val aiAssisted: Boolean
)

// ---------- spec encoders (GEN only; decoders live in EVAL_HELPERS) ----------

private fun targetSpecOf(targets: List<TargetParts>): String =
    targets.joinToString(";") { t -> encSpecStr(t.userId) + "|" + t.deviceId.toString() }

private fun cipherSpecOf(ciphertexts: List<CiphertextParts>): String =
    ciphertexts.joinToString(";") { c ->
        encSpecStr(c.userId) + "|" + c.deviceId.toString() + "|" +
            encSpecStr(c.ciphertextType) + "|" + encSpecStr(c.ciphertext)
    }

private fun envelopeSpecOf(envelopes: List<EnvelopeParts>): String =
    envelopes.joinToString(";") { e ->
        encSpecStr(e.userId) + "|" + e.deviceId.toString() + "|" +
            encSpecStr(e.ciphertextType) + "|" + encSpecStr(e.ciphertext)
    }

private fun uscMessageSpecOf(m: UscMessageParts): String =
    "M|" + encSpecStr(m.id) + "|" + encSpecStr(m.senderId) + "|" +
        encSpecStr(m.content) + "|" + m.type + "|" + m.timestamp.toString() + "|" +
        (if (m.aiAssisted) "1" else "0")

private fun uscMessageListSpecOf(messages: List<UscMessageParts>): String =
    messages.joinToString(";") { encSpecStr(uscMessageSpecOf(it)) }

// ---------- output encoders (eval only) ----------

private fun encTargetList(ids: List<String>): String =
    ids.joinToString(";") { encSpecStr(it) }

private fun encEnvelopeList(envelopes: List<EnvelopeParts>): String =
    envelopeSpecOf(envelopes)

private fun encEventParts(e: EventParts): String =
    encSpecStr(e.action) + "|" + encSpecStr(e.targetMessageId) + "|" +
        encSpecStr(e.status) + "|" +
        (e.throughMessageId?.let { "S" + encSpecStr(it) } ?: "N")

private fun encContentParts(c: ContentParts): String =
    encSpecStr(c.type) + "|" +
        c.attributes.joinToString(";") { (k, v) -> encSpecStr(k) + "=" + encSpecStr(v) }

private fun encIdList(ids: List<String>): String =
    ids.joinToString(";") { encSpecStr(it) }

// ---------- OutboxFailurePolicy oracle ----------

// Hand-written reimplementation. shouldDiscardStaleGroupControl
// needs an ApiException with GROUP_REVISION_MISMATCH and a kind in
// {"SENDER_KEY", "KEY_REQUEST"} -- note "SENDER_KEY_REQUEST" is
// NOT in that set. needsFreshEncryption needs an ApiException
// with either mismatch code. isSenderKeyCoveragePending is an
// exact string equality against the SENDER_KEY_COVERAGE_PENDING
// constant (null never matches).
private fun isGroupControlOracle(kind: String): Boolean =
    kind == "SENDER_KEY" || kind == "KEY_REQUEST"

private fun shouldDiscardStaleGroupControlOracle(
    isApiException: Boolean,
    serverCode: String?,
    kind: String
): Boolean =
    isApiException && serverCode == "GROUP_REVISION_MISMATCH" && isGroupControlOracle(kind)

private fun needsFreshEncryptionOracle(isApiException: Boolean, serverCode: String?): Boolean =
    isApiException &&
        (serverCode == "DEVICE_COVERAGE_MISMATCH" || serverCode == "GROUP_REVISION_MISMATCH")

private fun isSenderKeyCoveragePendingOracle(errorMessage: String?): Boolean =
    errorMessage == "sender_key_coverage_pending"

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

private val OFP_CODE_POOL116 = arrayOf<String?>(
    null, "GROUP_REVISION_MISMATCH", "DEVICE_COVERAGE_MISMATCH", "OTHER", "",
    "group_revision_mismatch", "GROUP_REVISION_MISMATCH ", "DEVICE_COVERAGE_MISMATCHx"
)

private val OFP_KIND_POOL116 = arrayOf(
    "SENDER_KEY", "KEY_REQUEST", "SENDER_KEY_REQUEST", "sender_key", "",
    "DATA", "KEY_REQUEST ", "SENDER_KEYX"
)

private val OFP_PENDING_POOL116 = arrayOf<String?>(
    null, "sender_key_coverage_pending", "sender_key_coverage_pending ",
    "SENDER_KEY_COVERAGE_PENDING", "", "sender_key_coverage_pendin",
    "sender_key_coverage_pendingx"
)

private fun genErrorParts116(rnd: Random, poolOnly: Boolean): Pair<Boolean, String?> {
    val isApi = rnd.nextBoolean()
    val code = if (poolOnly) OFP_CODE_POOL116[rnd.nextInt(OFP_CODE_POOL116.size)]
        else if (rnd.nextBoolean()) OFP_CODE_POOL116[rnd.nextInt(OFP_CODE_POOL116.size)]
        else randomToken(rnd)
    return isApi to code
}

private fun genOfpKind116(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly) OFP_KIND_POOL116[rnd.nextInt(OFP_KIND_POOL116.size)]
        else if (rnd.nextBoolean()) OFP_KIND_POOL116[rnd.nextInt(OFP_KIND_POOL116.size)]
        else randomToken(rnd)

private fun genPending116(rnd: Random, poolOnly: Boolean): String? =
    if (poolOnly) OFP_PENDING_POOL116[rnd.nextInt(OFP_PENDING_POOL116.size)]
        else if (rnd.nextBoolean()) OFP_PENDING_POOL116[rnd.nextInt(OFP_PENDING_POOL116.size)]
        else randomToken(rnd)

    @Test
    fun outboxFailurePolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (isApi, code) = genErrorParts116(rnd, poolOnly = false)
            val kind = genOfpKind116(rnd, poolOnly = false)
            val error = if (isApi) ApiException(ApiFailureKind.HTTP, serverCode = code)
                else RuntimeException("boom")
            assertEquals(
                shouldDiscardStaleGroupControlOracle(isApi, code, kind),
                OutboxFailurePolicy.shouldDiscardStaleGroupControl(error, kind)
            )
            assertEquals(
                needsFreshEncryptionOracle(isApi, code),
                OutboxFailurePolicy.needsFreshEncryption(error)
            )
            val pending = genPending116(rnd, poolOnly = false)
            assertEquals(
                isSenderKeyCoveragePendingOracle(pending),
                OutboxFailurePolicy.isSenderKeyCoveragePending(pending)
            )
        }
    }

    @Test
    fun outboxFailurePolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: the two mismatch codes and the group-control kinds dominate.
            val (isApi, code) = genErrorParts116(rnd, poolOnly = true)
            val kind = genOfpKind116(rnd, poolOnly = true)
            val error = if (isApi) ApiException(ApiFailureKind.HTTP, serverCode = code)
                else RuntimeException("boom")
            assertEquals(
                shouldDiscardStaleGroupControlOracle(isApi, code, kind),
                OutboxFailurePolicy.shouldDiscardStaleGroupControl(error, kind)
            )
            assertEquals(
                needsFreshEncryptionOracle(isApi, code),
                OutboxFailurePolicy.needsFreshEncryption(error)
            )
            val pending = genPending116(rnd, poolOnly = true)
            assertEquals(
                isSenderKeyCoveragePendingOracle(pending),
                OutboxFailurePolicy.isSenderKeyCoveragePending(pending)
            )
        }
    }

    @Test
    fun outboxFailurePolicyPinned() {
        val stale = ApiException(ApiFailureKind.HTTP, serverCode = "GROUP_REVISION_MISMATCH")
        val coverage = ApiException(ApiFailureKind.HTTP, serverCode = "DEVICE_COVERAGE_MISMATCH")
        val other = ApiException(ApiFailureKind.HTTP, serverCode = "OTHER")
        val nullCode = ApiException(ApiFailureKind.HTTP, serverCode = null)
        val plain = RuntimeException("boom")
        assertEquals(true, OutboxFailurePolicy.shouldDiscardStaleGroupControl(stale, "SENDER_KEY"))
        assertEquals(true, OutboxFailurePolicy.shouldDiscardStaleGroupControl(stale, "KEY_REQUEST"))
        // The wire kind SENDER_KEY_REQUEST is NOT a group control kind.
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(stale, "SENDER_KEY_REQUEST"))
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(stale, "DATA"))
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(stale, "sender_key"))
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(coverage, "SENDER_KEY"))
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(other, "SENDER_KEY"))
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(nullCode, "SENDER_KEY"))
        assertEquals(false, OutboxFailurePolicy.shouldDiscardStaleGroupControl(plain, "SENDER_KEY"))
        assertEquals(true, OutboxFailurePolicy.needsFreshEncryption(stale))
        assertEquals(true, OutboxFailurePolicy.needsFreshEncryption(coverage))
        assertEquals(false, OutboxFailurePolicy.needsFreshEncryption(other))
        assertEquals(false, OutboxFailurePolicy.needsFreshEncryption(nullCode))
        assertEquals(false, OutboxFailurePolicy.needsFreshEncryption(plain))
        assertEquals(true, OutboxFailurePolicy.isSenderKeyCoveragePending("sender_key_coverage_pending"))
        assertEquals(false, OutboxFailurePolicy.isSenderKeyCoveragePending(null))
        assertEquals(false, OutboxFailurePolicy.isSenderKeyCoveragePending(""))
        assertEquals(false, OutboxFailurePolicy.isSenderKeyCoveragePending("SENDER_KEY_COVERAGE_PENDING"))
        assertEquals(false, OutboxFailurePolicy.isSenderKeyCoveragePending("sender_key_coverage_pending "))
        // Oracle agrees with production on the kind/code edges.
        assertEquals(false, shouldDiscardStaleGroupControlOracle(true, "GROUP_REVISION_MISMATCH", "SENDER_KEY_REQUEST"))
        assertEquals(true, shouldDiscardStaleGroupControlOracle(true, "GROUP_REVISION_MISMATCH", "KEY_REQUEST"))
        assertEquals(true, needsFreshEncryptionOracle(true, "DEVICE_COVERAGE_MISMATCH"))
        assertEquals(false, needsFreshEncryptionOracle(false, "GROUP_REVISION_MISMATCH"))
    }
}
