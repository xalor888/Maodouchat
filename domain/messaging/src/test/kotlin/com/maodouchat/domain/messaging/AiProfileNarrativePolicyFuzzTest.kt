// Fuzz / random-oracle tests for AiProfileNarrativePolicy (checklist line 931,
// 117th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. statsHint / combineNarrative.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiProfileNarrativePolicyFuzzTest {

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

// ---------- block-117 common oracle helpers ----------

// Per-code-point uppercase (Character.toUpperCase per code point),
// mirroring String.uppercase(): the alphabet audit proves full ==
// simple over the generator alphabet, so no expansion occurs.

// Local hex encoder (same codec as Parse117.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse117.kt, so the codec lives here.
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

private fun encNullableSpecStr(s: String?): String =
    if (s == null) "N" else "S" + encSpecStr(s)

// ---------- wire value parts (oracle-side, never production) ----------

// kind: HTTP | TIMEOUT | NETWORK | INVALID_RESPONSE | UNEXPECTED | OTHER.
// For OTHER, serverMessage carries the RuntimeException message;
// statusCode / serverCode / reached / retryAfterSeconds are ignored.
private data class ErrParts(
    val kind: String,
    val statusCode: Int?,
    val serverCode: String?,
    val serverMessage: String?,
    val reached: Boolean,
    val retryAfterSeconds: Long?
)

private data class StatsParts(
    val messageCount: Int,
    val activeDays: Int,
    val morning: Int,
    val afternoon: Int,
    val evening: Int,
    val night: Int,
    val emojiCount: Int,
    val topTerms: List<String>
)

private data class AnnParts(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val acked: Boolean
)

private data class IoParts(val cls: String, val cause: IoParts?)

// ---------- spec encoders (GEN only; decoders live in EVAL_HELPERS) ----------

private fun errSpecOf(e: ErrParts): String =
    e.kind + "|" +
        (e.statusCode?.toString() ?: "N") + "|" +
        encNullableSpecStr(e.serverCode) + "|" +
        encNullableSpecStr(e.serverMessage) + "|" +
        (if (e.reached) "1" else "0") + "|" +
        (e.retryAfterSeconds?.toString() ?: "N")

// Action spec: variant tag + fields. SUM messages travel as ids only
// (describeAiOperation reads Message::id and nothing else).
private fun actionSpecOf(a: PendingAiAction): String = when (a) {
    is PendingAiAction.Rewrite ->
        "RW|" + encSpecStr(a.mode) + "|" + encNullableSpecStr(a.targetLanguage)
    is PendingAiAction.SuggestReplies -> "SR|" + encSpecStr(a.tone)
    is PendingAiAction.Summarize ->
        "SUM|" + a.scope.name + "|" +
            a.messages.map { it.id }.joinToString(";") { encSpecStr(it) } + "|" +
            encSpecStr(a.style)
    is PendingAiAction.TranscribeVoice -> "TV|" + encSpecStr(a.messageId)
    is PendingAiAction.TranslateMessage ->
        "TM|" + encSpecStr(a.messageId) + "|" + encSpecStr(a.targetLanguage)
    is PendingAiAction.AnalyzeImage ->
        "AI|" + encSpecStr(a.messageId) + "|" + a.mode.wireValue
    is PendingAiAction.AnalyzeFile ->
        "AF|" + encSpecStr(a.messageId) + "|" + a.mode.wireValue + "|" +
            encNullableSpecStr(a.question)
    is PendingAiAction.SemanticSearch ->
        "SS|" + encSpecStr(a.query) + "|" +
            a.candidateMessageIds.joinToString(";") { encSpecStr(it) }
    is PendingAiAction.GroupAssistant ->
        "GA|" + encSpecStr(a.query) + "|" + encSpecStr(a.mode)
}

private fun statsSpecOf(s: StatsParts): String =
    listOf(
        s.messageCount, s.activeDays, s.morning, s.afternoon, s.evening,
        s.night, s.emojiCount
    ).joinToString("|") + "|" +
        s.topTerms.joinToString(";") { encSpecStr(it) }

private fun annItemSpecOf(a: AnnParts): String =
    encSpecStr(a.id) + "|" + encSpecStr(a.title) + "|" +
        encSpecStr(a.content) + "|" + encSpecStr(a.level) + "|" +
        a.startsAt.toString() + "|" + a.expiresAt.toString() + "|" +
        encSpecStr(a.status) + "|" + (if (a.acked) "1" else "0")

private fun annListSpecOf(list: List<AnnParts>): String =
    list.joinToString(";") { encSpecStr(annItemSpecOf(it)) }

private fun ioSpecOf(e: IoParts): String =
    e.cls + "|" + (e.cause?.let { ioSpecOf(it) } ?: "N")

// ---------- output encoders (eval only) ----------

private fun encErrDetailed(code: String, retry: Long?, persisted: String): String =
    encSpecStr(code) + "|" + (retry?.toString() ?: "N") + "|" + encSpecStr(persisted)

private fun encDescriptor(d: AiOperationDescriptor?): String {
    if (d == null) return "N"
    val p = d.parameters
    return encSpecStr(d.type) + "|" +
        encNullableSpecStr(d.targetMessageId) + "|" +
        encNullableSpecStr(p.targetLanguage) + "|" +
        encNullableSpecStr(p.summaryScope) + "|" +
        p.messageIds.joinToString(";") { encSpecStr(it) } + "|" +
        encNullableSpecStr(p.analysisMode) + "|" +
        encNullableSpecStr(p.question)
}

private fun encIoResult(kind: String, reached: Boolean): String =
    kind + "|" + (if (reached) "B1" else "B0")

// ---------- AiProfileNarrativePolicy oracle ----------

// Hand-written reimplementation. statsHint interpolates the counts
// and the top-8 terms joined with "、"; combineNarrative joins
// with "。" then trims (28-unit Kotlin blank set) and takes 6000
// UTF-16 units.
private fun displayTermsOracle(terms: List<String>): String =
    terms.take(8).joinToString("、")

private fun statsHintOracle(stats: LocalStats): String =
    "消息 ${stats.messageCount} 条，活跃 ${stats.activeDays} 天，" +
        "主题 ${displayTermsOracle(stats.topTerms)}"

private fun combineNarrativeOracle(statsHint: String, summary: String): String =
    takeOracle(trimOracle("$statsHint。$summary"), 6000)

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

private val APN_COUNT_POOL117 = arrayOf(
    0, 1, 2, 10, 100, 1000, -1, -100, 2147483647, -2147483648
)

private val APN_TERM_POOL117 = arrayOf(
    "", "cat", "猫", "hello world", "a", "AB", "üser", "x y", "、",
    "topic", "Kotlin", "a b c", "  ", "😀", "term-1", "term_2"
)

private fun genStats117(rnd: Random, poolOnly: Boolean): StatsParts {
    fun n(): Int = if (poolOnly) APN_COUNT_POOL117[rnd.nextInt(APN_COUNT_POOL117.size)]
        else rnd.nextInt()
    val nTerms = if (poolOnly) rnd.nextInt(0, 12) else rnd.nextInt(0, 15)
    val terms = (0 until nTerms).map {
        if (poolOnly) APN_TERM_POOL117[rnd.nextInt(APN_TERM_POOL117.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 12))
    }
    return StatsParts(n(), n(), n(), n(), n(), n(), n(), terms)
}

private fun statsOfParts(s: StatsParts): LocalStats = LocalStats(
    messageCount = s.messageCount,
    activeDays = s.activeDays,
    morning = s.morning,
    afternoon = s.afternoon,
    evening = s.evening,
    night = s.night,
    emojiCount = s.emojiCount,
    topTerms = s.topTerms
)

    @Test
    fun aiProfileNarrativePolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val parts = genStats117(rnd, poolOnly = false)
            val stats = statsOfParts(parts)
            assertEquals(statsHintOracle(stats), AiProfileNarrativePolicy.statsHint(stats))
            val hint = if (rnd.nextBoolean()) "消息 3 条，活跃 2 天，主题 cat" else randomToken(rnd)
            val summary = if (rnd.nextBoolean()) "a short summary" else genTextOf(rnd, rnd.nextInt(0, 80))
            assertEquals(
                combineNarrativeOracle(hint, summary),
                AiProfileNarrativePolicy.combineNarrative(hint, summary)
            )
        }
    }

    @Test
    fun aiProfileNarrativePolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: term-count edges and extreme counts dominate.
            val parts = genStats117(rnd, poolOnly = true)
            val stats = statsOfParts(parts)
            assertEquals(statsHintOracle(stats), AiProfileNarrativePolicy.statsHint(stats))
            val hint = APN_TERM_POOL117[rnd.nextInt(APN_TERM_POOL117.size)]
            val summary = if (rnd.nextBoolean()) "" else "x".repeat(rnd.nextInt(0, 7000))
            assertEquals(
                combineNarrativeOracle(hint, summary),
                AiProfileNarrativePolicy.combineNarrative(hint, summary)
            )
        }
    }

    @Test
    fun aiProfileNarrativePolicyPinned() {
        assertEquals(
            "消息 3 条，活跃 2 天，主题 cat、dog",
            AiProfileNarrativePolicy.statsHint(
                LocalStats(messageCount = 3, activeDays = 2, topTerms = listOf("cat", "dog"))
            )
        )
        assertEquals(
            "消息 0 条，活跃 0 天，主题 ",
            AiProfileNarrativePolicy.statsHint(LocalStats())
        )
        // Only the first 8 terms are shown.
        val nine = (1..9).map { "t$it" }
        assertEquals(
            "消息 1 条，活跃 1 天，主题 t1、t2、t3、t4、t5、t6、t7、t8",
            AiProfileNarrativePolicy.statsHint(
                LocalStats(messageCount = 1, activeDays = 1, topTerms = nine)
            )
        )
        assertEquals(
            "消息 1 条，活跃 1 天，主题 t1、t2、t3、t4、t5、t6、t7、t8",
            statsHintOracle(LocalStats(messageCount = 1, activeDays = 1, topTerms = nine))
        )
        // combineNarrative joins with 。, trims, takes 6000 units.
        assertEquals(
            "hint。summary",
            AiProfileNarrativePolicy.combineNarrative("hint", "summary")
        )
        assertEquals(
            "hint。summary",
            combineNarrativeOracle("hint", "summary")
        )
        // trim() only strips the outer edges, inner spaces stay.
        assertEquals(
            "hint  。  summary",
            AiProfileNarrativePolicy.combineNarrative("  hint  ", "  summary  ")
        )
        assertEquals(
            "。x",
            AiProfileNarrativePolicy.combineNarrative("", "x")
        )
        val long = "y".repeat(7000)
        assertEquals(6000, AiProfileNarrativePolicy.combineNarrative("h", long).length)
        assertEquals(6000, combineNarrativeOracle("h", long).length)
        // Counts interpolate verbatim, negatives included.
        assertEquals(
            "消息 -5 条，活跃 -2 天，主题 ",
            AiProfileNarrativePolicy.statsHint(
                LocalStats(messageCount = -5, activeDays = -2)
            )
        )
    }
}
