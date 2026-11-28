// Fuzz / random-oracle tests for AiCostBillingHint (checklist line 931,
// 80th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. branch order is priority: streaming -> CANCELLED -> rate-limit family (== RATE_LIMITED / startsWith RATE_LIMIT / contains TOO_MANY) -> quota family (== QUOTA_EXCEEDED / contains QUOTA/BUDGET/PAYMENT/BILLING/INSUFFICIENT) -> outcome-unknown set -> scheduled auto-retry -> failed + non-blank -> NONE. shouldWarn is false on blank or exactly CONNECTION_NOT_ESTABLISHED, true on rate-limit / quota family / the warn set.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AiCostBillingHintFuzzTest {

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

// ---------- AiCostWaitPolicy / AiCostBillingHint oracle ----------

// AiCostErrorCodeCodec.baseErrorCode, hand-written: trim, head before
// ':', uppercase; blank head falls back to the whole raw uppercased.
private fun baseErrorCodeOracle(errorCode: String?): String {
    val raw = trimOracle(errorCode ?: "")
    if (raw.isEmpty()) return ""
    val head = uppercaseOracle(trimOracle(substringBeforeOracle(raw, ':')))
    return if (isBlankOracle(head)) uppercaseOracle(raw) else head
}

// AiCostErrorCodeCodec.embeddedRetryAfterSeconds, hand-written.
private fun embeddedRetryAfterSecondsOracle(errorCode: String?): Long? {
    val raw = trimOracle(errorCode ?: "")
    val sep = indexOfCharOracle(raw, ':')
    if (sep <= 0 || sep >= raw.length - 1) return null
    val tail = trimOracle(raw.substring(sep + 1))
    val seconds = parseLongRadixOracle(tail, 10) ?: return null
    if (seconds <= 0L) return null
    return if (seconds > 86_400L) 86_400L else seconds
}

private fun waitSecondsForOracle(
    errorCode: String?,
    serverRetryAfterSeconds: Long?,
    localRemainingMs: Long?
): Long {
    if (serverRetryAfterSeconds != null && serverRetryAfterSeconds > 0L) {
        return if (serverRetryAfterSeconds > 86_400L) 86_400L else serverRetryAfterSeconds
    }
    val embedded = embeddedRetryAfterSecondsOracle(errorCode)
    if (embedded != null) return embedded
    if (localRemainingMs != null && localRemainingMs > 0L) {
        val seconds = (localRemainingMs + 999L) / 1000L
        val atLeast = if (seconds < 1L) 1L else seconds
        return if (atLeast > 86_400L) 86_400L else atLeast
    }
    return when (baseErrorCodeOracle(errorCode)) {
        "QUOTA_EXCEEDED" -> 300L
        "RATE_LIMITED" -> 30L
        else -> 0L
    }
}

private fun billingHintForOracle(
    errorCode: String?,
    isStreaming: Boolean,
    isFailed: Boolean,
    hasScheduledAutoRetry: Boolean
): BillingHint {
    val code = baseErrorCodeOracle(errorCode)
    return when {
        isStreaming -> BillingHint.IN_FLIGHT_MAY_BILL
        code == "CANCELLED" -> BillingHint.CANCELLED_PARTIAL_MAY_BILL
        code == "RATE_LIMITED" || startsWithOracle(code, "RATE_LIMIT") ||
            containsOracle(code, "TOO_MANY") -> BillingHint.RATE_LIMITED_WAIT
        code == "QUOTA_EXCEEDED" || containsOracle(code, "QUOTA") ||
            containsOracle(code, "BUDGET") || containsOracle(code, "PAYMENT") ||
            containsOracle(code, "BILLING") || containsOracle(code, "INSUFFICIENT") ->
            BillingHint.QUOTA_EXCEEDED
        code == "OUTCOME_UNKNOWN" || code == "TIMEOUT" || code == "UNKNOWN" ||
            code == "INTERRUPTED" -> BillingHint.OUTCOME_UNKNOWN_MAY_BILL
        hasScheduledAutoRetry -> BillingHint.SAFE_AUTO_RETRY
        isFailed && !isBlankOracle(code) -> BillingHint.RETRY_MAY_BILL_AGAIN
        else -> BillingHint.NONE
    }
}

private fun shouldWarnRetryBillsOracle(errorCode: String?): Boolean {
    val code = baseErrorCodeOracle(errorCode)
    if (isBlankOracle(code) || code == "CONNECTION_NOT_ESTABLISHED") return false
    if (code == "RATE_LIMITED" || startsWithOracle(code, "RATE_LIMIT")) return true
    if (code == "QUOTA_EXCEEDED" || containsOracle(code, "QUOTA") ||
        containsOracle(code, "BUDGET") || containsOracle(code, "PAYMENT") ||
        containsOracle(code, "BILLING") || containsOracle(code, "INSUFFICIENT")
    ) return true
    return code == "OUTCOME_UNKNOWN" || code == "TIMEOUT" || code == "UNKNOWN" ||
        code == "INTERRUPTED" || code == "SERVER" || code == "EMPTY_RESULT" ||
        code == "INVALID_RESPONSE" || code == "CANCELLED"
}

private val COST_CODE_POOL80 = listOf(
    "RATE_LIMITED",
    "QUOTA_EXCEEDED",
    "CANCELLED",
    "rate_limited",
    "quota_exceeded",
    "cancelled",
    "Rate_Limited",
    "Quota_Exceeded",
    "Cancelled",
    "RATE_LIMIT",
    "RATE_LIMITED_FOO",
    "RATE_LIMIT_EXCEEDED",
    "TOO_MANY_REQUESTS",
    "too_many",
    "X_TOO_MANY_Y",
    "QUOTA",
    "MY_QUOTA_HIT",
    "BUDGET_EXCEEDED",
    "PAYMENT_REQUIRED",
    "BILLING_ERROR",
    "INSUFFICIENT_FUNDS",
    "INSUFFICIENT_QUOTA",
    "OUTCOME_UNKNOWN",
    "TIMEOUT",
    "UNKNOWN",
    "INTERRUPTED",
    "SERVER",
    "EMPTY_RESULT",
    "INVALID_RESPONSE",
    "CONNECTION_NOT_ESTABLISHED",
    "X_CONNECTION_NOT_ESTABLISHED",
    "OTHER",
    "FOO",
    "SOME_ERROR",
    "123",
    "ERR_42",
    "RATE_LIMITED:45",
    "RATE_LIMITED:0",
    "RATE_LIMITED:-5",
    "RATE_LIMITED:86400",
    "RATE_LIMITED:86401",
    "RATE_LIMITED:99999999999999999999",
    "QUOTA_EXCEEDED:300",
    "RATE_LIMITED:+45",
    "RATE_LIMITED: 45 ",
    "RATE_LIMITED:45:60",
    "RATE_LIMITED:",
    ":45",
    ":",
    "RATE_LIMITED:١٢٣",
    "RATE_LIMITED:abc",
    "RATE_LIMITED:4 5",
    " :45",
    "RATE_LIMITED :45",
    "  RATE_LIMITED  ",
    " QUOTA_EXCEEDED:60 ",
    "",
    " ",
    "   "
)

private fun genCostCode(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0 -> null
        1, 2, 3, 4, 5, 6 -> COST_CODE_POOL80[rnd.nextInt(COST_CODE_POOL80.size)]
        7 -> randomToken(rnd)
        8 -> randomString(rnd, 12)
        else -> COST_CODE_POOL80[rnd.nextInt(COST_CODE_POOL80.size)] + ":" + when (rnd.nextInt(8)) {
            0 -> "0"
            1 -> "-5"
            2 -> "+45"
            3 -> "86401"
            4 -> " 45 "
            5 -> "abc"
            6 -> "45:60"
            else -> rnd.nextInt(0, 200).toString()
        }
    }

private fun genCostServerSeconds(rnd: Random): Long? =
    when (rnd.nextInt(12)) {
        0 -> null
        1 -> 0L
        2 -> -1L
        3 -> -86_400L
        4 -> 1L
        5 -> 30L
        6 -> 45L
        7 -> 300L
        8 -> 86_400L
        9 -> 86_401L
        10 -> Long.MAX_VALUE
        else -> rnd.nextLong()
    }

private fun genCostLocalMs(rnd: Random): Long? =
    when (rnd.nextInt(14)) {
        0 -> null
        1 -> 0L
        2 -> -1L
        3 -> -1000L
        4 -> 1L
        5 -> 999L
        6 -> 1000L
        7 -> 1001L
        8 -> 1500L
        9 -> 86_400_000L
        10 -> 86_400_001L
        11 -> Long.MAX_VALUE
        12 -> Long.MAX_VALUE - 998L
        else -> rnd.nextLong()
    }

private fun genCostFlags(rnd: Random): Triple<Boolean, Boolean, Boolean> =
    Triple(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean())

    @Test
    fun billingRandom() {
        val rnd = Random(42)
        repeat(300) {
            val code = genCostCode(rnd)
            val (streaming, failed, autoRetry) = genCostFlags(rnd)
            assertEquals(
                billingHintForOracle(code, streaming, failed, autoRetry),
                AiCostBillingHint.billingHintFor(code, streaming, failed, autoRetry)
            )
            assertEquals(
                shouldWarnRetryBillsOracle(code),
                AiCostBillingHint.shouldWarnRetryBills(code)
            )
        }
    }

    @Test
    fun billingComboRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Auto-retry scheduled plus a quota/rate code: the earlier
            // branch must win over SAFE_AUTO_RETRY.
            val code = when (rnd.nextInt(6)) {
                0 -> "QUOTA_EXCEEDED"
                1 -> "RATE_LIMITED"
                2 -> "BUDGET_EXCEEDED"
                3 -> "TIMEOUT"
                4 -> "CANCELLED"
                else -> genCostCode(rnd)
            }
            val streaming = rnd.nextBoolean()
            val failed = rnd.nextBoolean()
            val autoRetry = rnd.nextBoolean()
            assertEquals(
                billingHintForOracle(code, streaming, failed, autoRetry),
                AiCostBillingHint.billingHintFor(code, streaming, failed, autoRetry)
            )
            assertEquals(
                shouldWarnRetryBillsOracle(code),
                AiCostBillingHint.shouldWarnRetryBills(code)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Streaming wins over every code branch.
        assertEquals(BillingHint.IN_FLIGHT_MAY_BILL, AiCostBillingHint.billingHintFor("QUOTA_EXCEEDED", isStreaming = true))
        assertEquals(BillingHint.IN_FLIGHT_MAY_BILL, AiCostBillingHint.billingHintFor(null, isStreaming = true))
        // Exact CANCELLED, then rate-limit family.
        assertEquals(BillingHint.CANCELLED_PARTIAL_MAY_BILL, AiCostBillingHint.billingHintFor("CANCELLED"))
        assertEquals(BillingHint.RATE_LIMITED_WAIT, AiCostBillingHint.billingHintFor("RATE_LIMITED"))
        assertEquals(BillingHint.RATE_LIMITED_WAIT, AiCostBillingHint.billingHintFor("rate_limited"))
        assertEquals(BillingHint.RATE_LIMITED_WAIT, AiCostBillingHint.billingHintFor("RATE_LIMIT_FOO"))
        assertEquals(BillingHint.RATE_LIMITED_WAIT, AiCostBillingHint.billingHintFor("TOO_MANY_REQUESTS"))
        assertEquals(BillingHint.RATE_LIMITED_WAIT, AiCostBillingHint.billingHintFor("too_many"))
        // Quota family (contains, after uppercasing).
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostBillingHint.billingHintFor("QUOTA_EXCEEDED"))
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostBillingHint.billingHintFor("my budget hit"))
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostBillingHint.billingHintFor("PAYMENT_REQUIRED"))
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostBillingHint.billingHintFor("billing error"))
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostBillingHint.billingHintFor("insufficient funds"))
        // Outcome-unknown set.
        assertEquals(BillingHint.OUTCOME_UNKNOWN_MAY_BILL, AiCostBillingHint.billingHintFor("TIMEOUT"))
        assertEquals(BillingHint.OUTCOME_UNKNOWN_MAY_BILL, AiCostBillingHint.billingHintFor("UNKNOWN"))
        assertEquals(BillingHint.OUTCOME_UNKNOWN_MAY_BILL, AiCostBillingHint.billingHintFor("INTERRUPTED"))
        // Auto-retry only fires when all earlier branches miss.
        assertEquals(BillingHint.SAFE_AUTO_RETRY, AiCostBillingHint.billingHintFor("OTHER", hasScheduledAutoRetry = true))
        assertEquals(BillingHint.QUOTA_EXCEEDED, AiCostBillingHint.billingHintFor("QUOTA_EXCEEDED", hasScheduledAutoRetry = true))
        assertEquals(BillingHint.RATE_LIMITED_WAIT, AiCostBillingHint.billingHintFor("RATE_LIMITED", hasScheduledAutoRetry = true))
        // Failed + non-blank code, else NONE.
        assertEquals(BillingHint.RETRY_MAY_BILL_AGAIN, AiCostBillingHint.billingHintFor("SOME_ERROR", isFailed = true))
        assertEquals(BillingHint.NONE, AiCostBillingHint.billingHintFor("", isFailed = true))
        assertEquals(BillingHint.NONE, AiCostBillingHint.billingHintFor(null, isFailed = true))
        assertEquals(BillingHint.NONE, AiCostBillingHint.billingHintFor("OTHER"))
        assertEquals(BillingHint.NONE, AiCostBillingHint.billingHintFor(null))
        // shouldWarn: blank / exact CONNECTION_NOT_ESTABLISHED -> false.
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills(null))
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills(""))
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills("   "))
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills("CONNECTION_NOT_ESTABLISHED"))
        // ...but a prefixed variant is not the exact code -> falls
        // through to the set check and is still false.
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills("X_CONNECTION_NOT_ESTABLISHED"))
        // Rate-limit / quota family -> true.
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("RATE_LIMITED"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("RATE_LIMIT_FOO"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("QUOTA_EXCEEDED"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("BUDGET"))
        // Warn set -> true; anything else -> false.
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("SERVER"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("EMPTY_RESULT"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("INVALID_RESPONSE"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("CANCELLED"))
        assertEquals(true, AiCostBillingHint.shouldWarnRetryBills("timeout"))
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills("OTHER"))
        assertEquals(false, AiCostBillingHint.shouldWarnRetryBills("TOO_MANY_REQUESTS"))
    }
}
