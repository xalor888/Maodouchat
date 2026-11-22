// Fuzz / random-oracle tests for ScheduledTimeCalculation (checklist line 931,
// 79th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. non-positive interval returns sendAt; whole-day intervals advance by calendar days (wall clock kept across DST), others by exact Duration; weekdaysOnly skips Sat/Sun; loop runs until strictly past now; resolveZone falls back to the system default on null/blank/unknown ids.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledTimeCalculationFuzzTest {

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


// ---------- extra helpers, block 79 ----------

private fun endsWithOracle(s: String, suffix: String): Boolean {
    if (suffix.length > s.length) return false
    for (i in suffix.indices) {
        if (s[s.length - suffix.length + i] != suffix[i]) return false
    }
    return true
}

// Literal (non-regex) replace-all, as String.replace(old, new).
private fun replaceLiteralOracle(s: String, old: String, new: String): String {
    if (old.isEmpty()) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i <= s.length - old.length) {
        var ok = true
        for (j in old.indices) {
            if (s[i + j] != old[j]) { ok = false; break }
        }
        if (ok) {
            sb.append(new)
            i += old.length
        } else {
            sb.append(s[i])
            i++
        }
    }
    while (i < s.length) {
        sb.append(s[i])
        i++
    }
    return sb.toString()
}

// Order-preserving dedupe, as List.distinct().
private fun distinctOracle(xs: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (x in xs) {
        var seen = false
        for (y in out) {
            if (equalsOracle(x, y)) { seen = true; break }
        }
        if (!seen) out.add(x)
    }
    return out
}

// Float clamp with the exact coerceIn semantics: NaN passes through
// (both comparisons false), -0.0f stays -0.0f.
private fun clamp01Oracle(v: Float): Float =
    if (v < 0f) 0f else if (v > 1f) 1f else v

// SHA-256 hex, lowercase, two digits per byte (hand-rolled nibbles,
// never String.format).
private fun sha256HexOracle(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    val hash = digest.digest()
    val hex = "0123456789abcdef"
    val sb = StringBuilder(hash.size * 2)
    for (b in hash) {
        sb.append(hex[(b.toInt() shr 4) and 0xF])
        sb.append(hex[b.toInt() and 0xF])
    }
    return sb.toString()
}


// ---------- ScheduledTimeCalculation oracle ----------

private fun resolveZoneOracle(timeZoneId: String?): java.time.ZoneId {
    if (timeZoneId == null || isBlankOracle(timeZoneId)) return java.time.ZoneId.systemDefault()
    return try {
        java.time.ZoneId.of(timeZoneId)
    } catch (e: Exception) {
        java.time.ZoneId.systemDefault()
    }
}

private fun nextRepeatSendAtOracle(
    sendAtMillis: Long,
    repeatIntervalMs: Long,
    weekdaysOnly: Boolean,
    timeZoneId: String?,
    nowMillis: Long,
): Long {
    if (repeatIntervalMs <= 0L) return sendAtMillis
    val zone = resolveZoneOracle(timeZoneId)
    val isWholeDays = (repeatIntervalMs % 86_400_000L) == 0L
    val daysStep = maxOf(repeatIntervalMs / 86_400_000L, 1L)
    var zdt = java.time.Instant.ofEpochMilli(sendAtMillis).atZone(zone)
    do {
        zdt = if (isWholeDays) {
            zdt.plusDays(daysStep)
        } else {
            zdt.plus(java.time.Duration.ofMillis(repeatIntervalMs))
        }
        if (weekdaysOnly) {
            while (zdt.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                zdt.dayOfWeek == java.time.DayOfWeek.SUNDAY
            ) {
                zdt = zdt.plusDays(1L)
            }
        }
    } while (zdt.toInstant().toEpochMilli() <= nowMillis)
    return zdt.toInstant().toEpochMilli()
}

private val SCHED_ZONE_POOL79 = listOf(
    "UTC", "Asia/Shanghai", "Asia/Tokyo", "America/New_York",
    "America/Los_Angeles", "Europe/London", "Europe/Berlin",
    "Australia/Sydney", "Pacific/Auckland", "GMT", "UT", "Z",
    "+05:30", "-08:00", "UTC+05:30", "", " ", "No/Such_Zone",
    "America/New_York ", " america/new_york", "UTC ", "GMT+2",
    "Europe/Paris", "Asia/Singapore", "America/Chicago"
)

private val SCHED_REPEAT_POOL79 = listOf(
    3_600_000L, 12 * 3_600_000L, 86_400_000L, 2 * 86_400_000L,
    7 * 86_400_000L, 86_400_001L, 86_399_999L, 90_000_000L,
    30 * 86_400_000L, 1L, 1000L, 60_000L, 86_400_000L * 3,
    25 * 3_600_000L, 23 * 3_600_000L
)

// DST-transition neighbourhoods, 2025 (epoch millis, UTC).
// US spring 2025-03-09 07:00Z, US fall 2025-11-02 06:00Z,
// EU spring 2025-03-30 01:00Z, EU fall 2025-10-26 01:00Z,
// AU fall 2025-04-06 16:00Z, AU spring 2025-10-05 16:00Z.
private val SCHED_ANCHOR_POOL79 = listOf(
    1741503600000L, 1741500000000L, 1741507200000L,
    1762063200000L, 1762059600000L, 1762066800000L,
    1743296400000L, 1743292800000L, 1743300000000L,
    1761440400000L, 1761436800000L, 1761444000000L,
    1743868800000L, 1743865200000L, 1743872400000L,
    1759593600000L, 1759590000000L, 1759597200000L,
    1735689600000L, 1767225600000L, 1700000000000L, 1800000000000L
)

private fun genSchedSendAt(rnd: Random): Long =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> SCHED_ANCHOR_POOL79[rnd.nextInt(SCHED_ANCHOR_POOL79.size)] +
            rnd.nextLong(-86_400_000L, 86_400_001L)
        6, 7 -> rnd.nextLong(1700000000000L, 1800000000000L)
        else -> rnd.nextLong(1735689600000L, 1767225600001L)
    }

private fun genSchedRepeat(rnd: Random): Long =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> SCHED_REPEAT_POOL79[rnd.nextInt(SCHED_REPEAT_POOL79.size)]
        6 -> rnd.nextLong(1L, 30L * 86_400_000L + 1L)
        7 -> rnd.nextLong(-86_400_000L, 86_400_001L)
        8 -> 0L
        else -> rnd.nextLong(-1000L, 1001L)
    }

private fun genSchedZone(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0 -> null
        1, 2, 3, 4, 5, 6, 7 -> SCHED_ZONE_POOL79[rnd.nextInt(SCHED_ZONE_POOL79.size)]
        8 -> randomToken(rnd)
        else -> randomString(rnd, 10)
    }

private fun genSchedNow(rnd: Random, sendAt: Long, repeatMs: Long): Long {
    // Keep the repeat loop short: now stays within ~100 steps of
    // sendAt (a 1ms interval with a 60-day horizon would loop
    // billions of times in production too).
    val horizon = if (repeatMs <= 0L) 60L * 86_400_000L else minOf(60L * 86_400_000L, repeatMs * 100L + 1L)
    return when (rnd.nextInt(10)) {
        0, 1, 2, 3 -> sendAt + rnd.nextLong(0L, horizon + 1L)
        4 -> sendAt - rnd.nextLong(0L, 86_400_000L + 1L)
        5 -> sendAt
        6 -> sendAt + 1000L
        else -> sendAt + rnd.nextLong(0L, horizon + 1L)
    }
}

    @Test
    fun schedRandom() {
        val rnd = Random(42)
        repeat(300) {
            val sendAt = genSchedSendAt(rnd)
            val repeat = genSchedRepeat(rnd)
            val weekdaysOnly = rnd.nextBoolean()
            val zone = genSchedZone(rnd)
            val now = genSchedNow(rnd, sendAt, repeat)
            assertEquals(
                nextRepeatSendAtOracle(sendAt, repeat, weekdaysOnly, zone, now),
                ScheduledTimeCalculation.nextRepeatSendAt(sendAt, repeat, weekdaysOnly, zone, now)
            )
            assertEquals(resolveZoneOracle(zone), ScheduledTimeCalculation.resolveZone(zone))
        }
    }

    @Test
    fun schedZoneRandom() {
        val rnd = Random(7)
        repeat(300) {
            val zone = genSchedZone(rnd)
            assertEquals(resolveZoneOracle(zone), ScheduledTimeCalculation.resolveZone(zone))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Non-positive interval returns sendAt unchanged.
        assertEquals(
            1_700_000_000_000L,
            ScheduledTimeCalculation.nextRepeatSendAt(1_700_000_000_000L, 0L, false, "UTC", 1_800_000_000_000L)
        )
        assertEquals(
            1_700_000_000_000L,
            ScheduledTimeCalculation.nextRepeatSendAt(1_700_000_000_000L, -5L, true, "UTC", 1_800_000_000_000L)
        )
        // Daily repeat keeps the wall clock across the US spring-forward
        // gap: 2025-03-08 02:30 New York + 1 day = 2025-03-09 03:30 EDT
        // (02:30 does not exist on the 9th; the gap shifts it forward).
        val gapDay = java.time.ZonedDateTime.of(2025, 3, 8, 2, 30, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val afterGap = ScheduledTimeCalculation.nextRepeatSendAt(
            gapDay, 86_400_000L, false, "America/New_York", gapDay
        )
        val gapZdt = java.time.Instant.ofEpochMilli(afterGap).atZone(java.time.ZoneId.of("America/New_York"))
        assertEquals(2025, gapZdt.year)
        assertEquals(3, gapZdt.monthValue)
        assertEquals(9, gapZdt.dayOfMonth)
        assertEquals(3, gapZdt.hour)
        assertEquals(30, gapZdt.minute)
        // Fall-back: 2025-11-01 02:30 occurs twice; +1 day lands on the
        // second 02:30 (EST) on 2025-11-02, same wall clock.
        val fallDay = java.time.ZonedDateTime.of(2025, 11, 1, 2, 30, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val afterFall = ScheduledTimeCalculation.nextRepeatSendAt(
            fallDay, 86_400_000L, false, "America/New_York", fallDay
        )
        val fallZdt = java.time.Instant.ofEpochMilli(afterFall).atZone(java.time.ZoneId.of("America/New_York"))
        assertEquals(11, fallZdt.monthValue)
        assertEquals(2, fallZdt.dayOfMonth)
        assertEquals(2, fallZdt.hour)
        assertEquals(30, fallZdt.minute)
        // Weekdays only: Friday 2026-10-30 09:00 UTC + daily -> Monday 2026-11-02.
        val friday = java.time.ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
        val next = ScheduledTimeCalculation.nextRepeatSendAt(
            friday, 24 * 60 * 60 * 1000L, true, "UTC", friday + 1000L
        )
        val zdt = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.of("UTC"))
        assertEquals(2026, zdt.year)
        assertEquals(11, zdt.monthValue)
        assertEquals(2, zdt.dayOfMonth)
        // Weekdays only from a Saturday lands on Monday, not Sunday.
        val saturday = java.time.ZonedDateTime.of(2026, 10, 31, 9, 0, 0, 0, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
        val monday = ScheduledTimeCalculation.nextRepeatSendAt(
            saturday, 24 * 60 * 60 * 1000L, true, "UTC", saturday + 1000L
        )
        val mzdt = java.time.Instant.ofEpochMilli(monday).atZone(java.time.ZoneId.of("UTC"))
        assertEquals(2, mzdt.dayOfMonth)
        assertEquals(11, mzdt.monthValue)
        // Sub-day interval is exact-duration arithmetic, not wall clock:
        // 12h across the spring gap moves the wall clock by 11 hours.
        val springEve = java.time.ZonedDateTime.of(2025, 3, 8, 20, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val plus12 = ScheduledTimeCalculation.nextRepeatSendAt(
            springEve, 12 * 3_600_000L, false, "America/New_York", springEve
        )
        val p12 = java.time.Instant.ofEpochMilli(plus12).atZone(java.time.ZoneId.of("America/New_York"))
        assertEquals(9, p12.dayOfMonth)
        assertEquals(9, p12.hour)
        // resolveZone: null / blank / unknown fall back to the default.
        assertEquals(java.time.ZoneId.systemDefault(), ScheduledTimeCalculation.resolveZone(null))
        assertEquals(java.time.ZoneId.systemDefault(), ScheduledTimeCalculation.resolveZone(""))
        assertEquals(java.time.ZoneId.systemDefault(), ScheduledTimeCalculation.resolveZone("   "))
        assertEquals(java.time.ZoneId.systemDefault(), ScheduledTimeCalculation.resolveZone("No/Such_Zone"))
        assertEquals(java.time.ZoneId.of("UTC"), ScheduledTimeCalculation.resolveZone("UTC"))
        assertEquals(java.time.ZoneId.of("Asia/Shanghai"), ScheduledTimeCalculation.resolveZone("Asia/Shanghai"))
        // Whole-day detection is exact: 86400001 ms is NOT whole days.
        val base = java.time.ZonedDateTime.of(2025, 3, 8, 10, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val plusDayMs = ScheduledTimeCalculation.nextRepeatSendAt(
            base, 86_400_001L, false, "America/New_York", base
        )
        // Exact +86400001ms from 10:00 EST Mar 8 = 11:00:00.001 EDT Mar 9.
        val dm = java.time.Instant.ofEpochMilli(plusDayMs).atZone(java.time.ZoneId.of("America/New_York"))
        assertEquals(9, dm.dayOfMonth)
        assertEquals(11, dm.hour)
        assertEquals(0, dm.minute)
        assertEquals(0, dm.second)
        assertEquals(1, dm.nano / 1_000_000)
    }
}
