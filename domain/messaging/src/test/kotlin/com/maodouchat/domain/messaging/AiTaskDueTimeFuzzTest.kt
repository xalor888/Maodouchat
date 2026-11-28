// Fuzz / random-oracle tests for AiTaskDueTime (checklist line 931,
// 76th block). Test-only, zero production changes. The oracle is
// hand-written: plain string building with the exact templates
// read from the production file. formatAiTaskDue is "yyyy-MM-dd HH:mm" (test pins UTC + Locale.UK so the cached formatter is built under UTC); aiTaskDueLabel drops a blank dueText but keeps a blank formattedDueAt, dedupes, nulls on blank join; isAiTaskOverdue is strict dueAt < now.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AiTaskDueTimeFuzzTest {

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

// ---------- Kotlin trim() oracle (28-unit whitespace set) ----------

private fun trimKotlinOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// ---------- AiTaskDueTime oracle ----------

// Civil-from-days (Hinnant algorithm) over proleptic Gregorian
// dates, UTC. Exact for every instant at or after the 1582-10-15
// Gregorian cutover, where SimpleDateFormat agrees with it; the
// generator never goes earlier. Hand-written, never SimpleDateFormat.
private fun pad2Oracle(v: Long): String {
    val s = v.toString()
    return if (s.length >= 2) s else "0" + s
}

private fun padYearOracle(year: Long): String {
    var s = year.toString()
    while (s.length < 4) s = "0" + s
    return s
}

private fun formatDueOracle(millis: Long): String {
    val days = Math.floorDiv(millis, 86_400_000L)
    val secsOfDay = Math.floorMod(millis, 86_400_000L) / 1000L
    val hour = secsOfDay / 3600L
    val minute = (secsOfDay % 3600L) / 60L
    val z = days + 719468L
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10L) mp + 3L else mp - 9L
    val year = if (m <= 2L) y + 1L else y
    return padYearOracle(year) + "-" + pad2Oracle(m) + "-" + pad2Oracle(d) +
        " " + pad2Oracle(hour) + ":" + pad2Oracle(minute)
}

private fun dueLabelOracle(dueText: String?, formattedDueAt: String?): String? {
    // listOfNotNull(dueText?.takeIf(isNotBlank), formattedDueAt):
    // a blank dueText is dropped, a blank formattedDueAt is KEPT.
    val parts = mutableListOf<String>()
    if (dueText != null && !isBlankOracle(dueText)) parts.add(dueText)
    if (formattedDueAt != null) parts.add(formattedDueAt)
    // distinct(): exact equality, first occurrence wins.
    val distinct = mutableListOf<String>()
    for (p in parts) {
        var seen = false
        for (q in distinct) {
            if (equalsOracle(p, q)) { seen = true; break }
        }
        if (!seen) distinct.add(p)
    }
    // joinToString(" · "), then null when the join is blank.
    val sb = StringBuilder()
    for (i in distinct.indices) {
        if (i > 0) sb.append(" · ")
        sb.append(distinct[i])
    }
    val joined = sb.toString()
    return if (isBlankOracle(joined)) null else joined
}

private fun overdueOracle(isCompleted: Boolean, dueAt: Long?, nowMillis: Long): Boolean =
    !isCompleted && dueAt != null && dueAt < nowMillis

// First Gregorian instant (1582-10-15T00:00:00Z); SimpleDateFormat
// agrees with the proleptic-Gregorian oracle from here on.
private val DUE_MIN_MILLIS: Long = -12_219_292_800_000L
private val DUE_MAX_MILLIS: Long = 253_402_300_799_999L

private fun genDueMillis(rnd: Random): Long =
    when (rnd.nextInt(10)) {
        0 -> 0L
        1 -> -1L
        2 -> 86_400_000L
        3 -> rnd.nextLong(0L, 2_000_000_000_000L)
        4 -> rnd.nextLong(-1_000_000_000_000L, 0L)
        5 -> 951_782_400_000L
        6 -> 978_307_200_000L
        7 -> DUE_MAX_MILLIS
        8 -> DUE_MIN_MILLIS
        else -> rnd.nextLong(DUE_MIN_MILLIS, DUE_MAX_MILLIS + 1L)
    }

private fun genDueText(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0 -> null
        1 -> ""
        2 -> " "
        3 -> "   "
        4 -> "tomorrow"
        5 -> "2026-04-10 14:30"
        6 -> "x"
        7 -> randomWord(rnd)
        8 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genDueFormatted(rnd: Random): String? =
    when (rnd.nextInt(8)) {
        0 -> null
        1 -> ""
        2 -> " "
        3 -> "2026-04-10 14:30"
        4 -> "x"
        5 -> randomWord(rnd)
        6 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genDueAt(rnd: Random): Long? =
    when (rnd.nextInt(8)) {
        0 -> null
        1 -> 0L
        2 -> 1000L
        3 -> Long.MIN_VALUE
        4 -> Long.MAX_VALUE
        5 -> rnd.nextLong()
        6 -> rnd.nextLong(-1_000_000L, 1_000_000L)
        else -> rnd.nextLong(0L, 4_000_000_000_000L)
    }

    @Test
    fun dueRandom() {
        val rnd = Random(42)
        repeat(300) {
            val dueText = genDueText(rnd)
            val formatted = genDueFormatted(rnd)
            val completed = rnd.nextBoolean()
            val dueAt = genDueAt(rnd)
            val now = genDueAt(rnd) ?: 2_000_000_000_000L
            assertEquals(
                dueLabelOracle(dueText, formatted),
                aiTaskDueLabel(dueText, formatted)
            )
            assertEquals(
                overdueOracle(completed, dueAt, now),
                isAiTaskOverdue(completed, dueAt, now)
            )
        }
    }

    @Test
    fun dueFormatRandom() {
        // The production formatter caches one SimpleDateFormat per
        // locale in a ThreadLocal, built with the default timezone
        // at construction time; Locale.UK is a cache key no other
        // test uses, so the formatter is built under the UTC default
        // set here. The old default is restored afterwards.
        val oldTz = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val rnd = Random(7)
            repeat(300) {
                val millis = genDueMillis(rnd)
                assertEquals(
                    formatDueOracle(millis),
                    formatAiTaskDue(millis, java.util.Locale.UK)
                )
            }
        } finally {
            java.util.TimeZone.setDefault(oldTz)
        }
    }
    @Test
    fun pinnedEdgeCases() {
        val oldTz = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("1970-01-01 00:00", formatAiTaskDue(0L, java.util.Locale.UK))
            assertEquals("1969-12-31 23:59", formatAiTaskDue(-1L, java.util.Locale.UK))
            assertEquals("1970-01-02 00:00", formatAiTaskDue(86_400_000L, java.util.Locale.UK))
            // Leap day, and the last representable minute of year 9999.
            assertEquals("2000-02-29 00:00", formatAiTaskDue(951_782_400_000L, java.util.Locale.UK))
            assertEquals("9999-12-31 23:59", formatAiTaskDue(253_402_300_799_999L, java.util.Locale.UK))
            // The Gregorian cutover instant itself.
            assertEquals("1582-10-15 00:00", formatAiTaskDue(-12_219_292_800_000L, java.util.Locale.UK))
            assertEquals("2026-04-10 14:30", formatAiTaskDue(1_775_831_400_000L, java.util.Locale.UK))
        } finally {
            java.util.TimeZone.setDefault(oldTz)
        }
        // Label: blank dueText drops, blank formattedDueAt is kept
        // in the join; identical parts dedupe; all-blank is null.
        assertEquals("tomorrow · 2026-04-10 14:30", aiTaskDueLabel("tomorrow", "2026-04-10 14:30"))
        assertEquals("2026-04-10 14:30", aiTaskDueLabel("2026-04-10 14:30", "2026-04-10 14:30"))
        assertEquals("2026-04-10 14:30", aiTaskDueLabel("   ", "2026-04-10 14:30"))
        assertEquals("x ·  ", aiTaskDueLabel("x", " "))
        assertEquals(null, aiTaskDueLabel(null, null))
        assertEquals(null, aiTaskDueLabel("  ", null))
        assertEquals(null, aiTaskDueLabel(null, " "))
        assertEquals(null, aiTaskDueLabel("", ""))
        // Overdue is strict: equal is not overdue, completed never is.
        assertEquals(true, isAiTaskOverdue(false, 1000L, 2000L))
        assertEquals(false, isAiTaskOverdue(false, 2000L, 2000L))
        assertEquals(false, isAiTaskOverdue(true, 1000L, 2000L))
        assertEquals(false, isAiTaskOverdue(false, null, 2000L))
        assertEquals(false, isAiTaskOverdue(false, Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(true, isAiTaskOverdue(false, Long.MIN_VALUE, Long.MIN_VALUE + 1L))
    }
}
