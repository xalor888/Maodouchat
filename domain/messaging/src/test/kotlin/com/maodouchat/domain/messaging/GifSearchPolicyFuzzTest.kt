// Fuzz / random-oracle tests for GifSearchPolicy (checklist line 931,
// 68th block). Test-only, zero production changes. The oracle is
// hand-written: trim over the probed 28-unit Kotlin whitespace set,
// per-code-point lowercase, unit-by-unit equality, hand-written
// contains / contains-ignore-case, hand-written take (UTF-16 units),
// explicit membership loops. normalizeQuery trims + lowercases + takes 80; matches tries the raw, '_'->space and '-'->space arms; filterAndSort is recent-first, date desc, lowercase-name asc (stable), limit clamped to >= 1; pushRecent trims, dedupes, moves to front.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GifSearchPolicyFuzzTest {

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
    0x1E00 to 0x1E9A, 0x1E9C to 0x1EFF,
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

// ---------- GifSearchPolicy oracle ----------

private fun gifNormalizeQueryOracle(raw: String?): String =
    takeOracle(lowercaseOracle(trimOracle(raw ?: "")), 80)

private fun gifMatchesOracle(item: LocalGifItem, query: String): Boolean {
    val q = gifNormalizeQueryOracle(query)
    if (q.isEmpty()) return true
    val name = lowercaseOracle(item.displayName)
    return containsOracle(name, q) ||
        containsOracle(replaceCharOracle(name, '_', ' '), q) ||
        containsOracle(replaceCharOracle(name, '-', ' '), q)
}

private fun gifFilterAndSortOracle(
    items: List<LocalGifItem>,
    query: String,
    recentIds: List<String>,
    limit: Int
): List<LocalGifItem> {
    val q = gifNormalizeQueryOracle(query)
    // associate: a duplicate recent id keeps its LAST index.
    val recentIndex = mutableMapOf<String, Int>()
    for ((index, id) in recentIds.withIndex()) recentIndex[id] = index
    val filtered = items.filter { gifMatchesOracle(it, q) }
    // Stable sort: recent index asc (missing = MAX), date desc,
    // lowercase name asc, then input position (sortedWith is stable).
    val sorted = filtered.withIndex().sortedWith(Comparator { a, b ->
        val ra = recentIndex[a.value.id] ?: Int.MAX_VALUE
        val rb = recentIndex[b.value.id] ?: Int.MAX_VALUE
        val byRecent = ra.compareTo(rb)
        if (byRecent != 0) return@Comparator byRecent
        val byDate = b.value.dateAddedSec.compareTo(a.value.dateAddedSec)
        if (byDate != 0) return@Comparator byDate
        val byName = compareOracle(
            lowercaseOracle(a.value.displayName), lowercaseOracle(b.value.displayName)
        )
        if (byName != 0) return@Comparator byName
        a.index.compareTo(b.index)
    })
    return sorted.take(coerceAtLeastOracle(limit, 1)).map { it.value }
}

private fun gifPushRecentOracle(existing: List<String>, gifId: String, max: Int): List<String> {
    val id = trimOracle(gifId)
    if (id.isEmpty()) return existing
    val out = mutableListOf(id)
    for (e in existing) {
        if (!equalsOracle(e, id)) out.add(e)
    }
    return out.take(coerceAtLeastOracle(max, 1))
}

private fun genGifItem(rnd: Random, idSuffix: Int): LocalGifItem {
    val id = "gif" + idSuffix
    val name = when (rnd.nextInt(6)) {
        0 -> randomWord(rnd)
        1 -> randomWord(rnd) + "_" + randomWord(rnd)
        2 -> randomWord(rnd) + "-" + randomWord(rnd)
        3 -> randomWord(rnd) + " " + randomWord(rnd)
        4 -> randomString(rnd, 20)
        else -> randomToken(rnd) + randomToken(rnd)
    }
    val date = when (rnd.nextInt(8)) {
        0 -> Long.MIN_VALUE; 1 -> -1L; 2 -> 0L; 3 -> 1L
        4 -> 5L; 5 -> 10L; 6 -> Long.MAX_VALUE
        else -> rnd.nextLong()
    }
    val size = when (rnd.nextInt(4)) {
        0 -> 0L; 1 -> Long.MAX_VALUE; 2 -> Long.MIN_VALUE
        else -> rnd.nextLong()
    }
    return LocalGifItem(id, "content://" + id, name, sizeBytes = size, dateAddedSec = date)
}

private fun genGifNormalize(rnd: Random): String? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> randomWord(rnd)
        2 -> "  " + randomWord(rnd) + "  "
        3 -> randomString(rnd, 120)
        else -> randomString(rnd, 90)
    }
}

private fun genGifMatches(rnd: Random): Pair<LocalGifItem, String> {
    val item = genGifItem(rnd, rnd.nextInt(1000))
    val query = when (rnd.nextInt(5)) {
        0 -> randomWord(rnd)
        1 -> " " + randomWord(rnd) + " "
        2 -> randomString(rnd, 90)
        3 -> item.displayName
        else -> randomToken(rnd)
    }
    return item to query
}

private fun genGifFilter(rnd: Random): Triple<List<LocalGifItem>, String, Pair<List<String>, Int>> {
    val n = rnd.nextInt(9)
    val items = (0 until n).map { genGifItem(rnd, it) }
    val query = when (rnd.nextInt(5)) {
        0 -> ""
        1 -> "   "
        2 -> randomWord(rnd)
        3 -> if (items.isEmpty()) randomWord(rnd) else items[rnd.nextInt(items.size)].displayName
        else -> randomString(rnd, 90)
    }
    val ids = items.map { it.id }
    val recent = mutableListOf<String>()
    repeat(rnd.nextInt(6)) {
        recent.add(when (rnd.nextInt(4)) {
            0 -> if (ids.isEmpty()) randomToken(rnd) else ids[rnd.nextInt(ids.size)]
            1 -> randomToken(rnd)
            2 -> if (recent.isEmpty()) randomToken(rnd) else recent[rnd.nextInt(recent.size)]
            else -> if (ids.isEmpty()) randomToken(rnd) else ids[rnd.nextInt(ids.size)]
        })
    }
    val limit = when (rnd.nextInt(10)) {
        0 -> -100; 1 -> -1; 2 -> 0; 3 -> 1; 4 -> 2; 5 -> 3
        6 -> 5; 7 -> 160; 8 -> 1000; else -> Int.MAX_VALUE
    }
    return Triple(items, query, recent to limit)
}

private fun genGifPush(rnd: Random): Triple<List<String>, String, Int> {
    val n = rnd.nextInt(6)
    val existing = (0 until n).map { randomToken(rnd) }
    val gifId = when (rnd.nextInt(5)) {
        0 -> randomToken(rnd)
        1 -> " " + randomToken(rnd) + " "
        2 -> if (existing.isEmpty()) randomToken(rnd) else existing[rnd.nextInt(existing.size)]
        3 -> "  "
        else -> randomString(rnd, 10)
    }
    val max = when (rnd.nextInt(8)) {
        0 -> -2; 1 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 48; 6 -> 100
        else -> rnd.nextInt(200)
    }
    return Triple(existing, gifId, max)
}

    @Test
    fun normalizeQueryRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = genGifNormalize(rnd)
            assertEquals(gifNormalizeQueryOracle(raw), GifSearchPolicy.normalizeQuery(raw))
        }
    }

    @Test
    fun matchesRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (item, query) = genGifMatches(rnd)
            assertEquals(gifMatchesOracle(item, query), GifSearchPolicy.matches(item, query))
        }
    }

    @Test
    fun filterAndSortRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (items, query, recentAndLimit) = genGifFilter(rnd)
            val (recent, limit) = recentAndLimit
            assertEquals(
                gifFilterAndSortOracle(items, query, recent, limit),
                GifSearchPolicy.filterAndSort(items, query, recent, limit)
            )
        }
    }

    @Test
    fun pushRecentRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (existing, gifId, max) = genGifPush(rnd)
            assertEquals(gifPushRecentOracle(existing, gifId, max), GifSearchPolicy.pushRecent(existing, gifId, max))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // normalizeQuery pinned
        assertEquals("", GifSearchPolicy.normalizeQuery(null))
        assertEquals("", GifSearchPolicy.normalizeQuery(""))
        assertEquals("cat", GifSearchPolicy.normalizeQuery("  CAT "))
        assertEquals(80, GifSearchPolicy.normalizeQuery("a".repeat(100)).length)
        assertEquals("a".repeat(80), GifSearchPolicy.normalizeQuery("a".repeat(100)))
        assertEquals("\uD83D\uDE00".repeat(40), GifSearchPolicy.normalizeQuery("\uD83D\uDE00".repeat(41)))
        assertEquals(80, GifSearchPolicy.normalizeQuery("a\uD83D\uDE00".repeat(50)).length)
        // İ lowercases to i + combining dot (full mapping, probed).
        assertEquals("li\u0307ght", GifSearchPolicy.normalizeQuery("LİGHT"))
        // NBSP / U+2007 trim, NEL / ZWSP do not (probed 28-unit set).
        assertEquals("cat", GifSearchPolicy.normalizeQuery("\u00A0 cat \u2007"))
        assertEquals("\u0085cat\u0085", GifSearchPolicy.normalizeQuery("\u0085cat\u0085"))
        assertEquals("\u200Bcat\u200B", GifSearchPolicy.normalizeQuery("\u200Bcat\u200B"))
        assertEquals("a  b", GifSearchPolicy.normalizeQuery("  a  b  "))

        // matches pinned
        val cat = LocalGifItem("1", "content://1", "funny_cat")
        assertTrue(GifSearchPolicy.matches(cat, "funny cat"))
        assertTrue(GifSearchPolicy.matches(LocalGifItem("2", "content://2", "funny-cat"), "funny cat"))
        assertTrue(GifSearchPolicy.matches(cat, ""))
        assertTrue(GifSearchPolicy.matches(cat, "   "))
        assertTrue(GifSearchPolicy.matches(LocalGifItem("3", "content://3", "CAT"), "cat"))
        assertTrue(GifSearchPolicy.matches(cat, "funny_cat"))
        assertTrue(GifSearchPolicy.matches(LocalGifItem("4", "content://4", "a-b"), "a b"))
        // Query itself is normalized (trim + lowercase + take 80).
        assertTrue(GifSearchPolicy.matches(LocalGifItem("5", "content://5", "a".repeat(100)), "a".repeat(100)))
        assertFalse(GifSearchPolicy.matches(LocalGifItem("6", "content://6", "a".repeat(79) + "b"), "a".repeat(100)))
        // İ: the name lowercases to li + combining dot, "light" misses.
        assertTrue(GifSearchPolicy.matches(LocalGifItem("7", "content://7", "LİGHT"), "li\u0307ght"))
        assertFalse(GifSearchPolicy.matches(LocalGifItem("7", "content://7", "LİGHT"), "LIGHT"))
        assertFalse(GifSearchPolicy.matches(cat, "dog"))

        // filterAndSort pinned
        val a = LocalGifItem("a", "content://a", "cat", dateAddedSec = 5L)
        val b = LocalGifItem("b", "content://b", "cat", dateAddedSec = 5L)
        // Duplicate recent ids keep the LAST index: a(1) before b(2).
        assertEquals(listOf("a", "b"), GifSearchPolicy.filterAndSort(listOf(a, b), "cat", listOf("b", "a", "b")).map { it.id })
        // Full tie keeps input order (stable sort).
        val x = LocalGifItem("x", "content://x", "cat", dateAddedSec = 5L)
        val y = LocalGifItem("y", "content://y", "cat", dateAddedSec = 5L)
        val z = LocalGifItem("z", "content://z", "cat", dateAddedSec = 5L)
        assertEquals(listOf("x", "y", "z"), GifSearchPolicy.filterAndSort(listOf(x, y, z), "cat").map { it.id })
        // Date desc, then lowercase name asc.
        val old = LocalGifItem("o", "content://o", "cat", dateAddedSec = 5L)
        val new = LocalGifItem("n", "content://n", "cat", dateAddedSec = 10L)
        assertEquals(listOf("n", "o"), GifSearchPolicy.filterAndSort(listOf(old, new), "cat").map { it.id })
        val upper = LocalGifItem("u", "content://u", "B", dateAddedSec = 5L)
        val lower = LocalGifItem("l", "content://l", "a", dateAddedSec = 5L)
        assertEquals(listOf("l", "u"), GifSearchPolicy.filterAndSort(listOf(upper, lower), "").map { it.id })
        // limit.coerceAtLeast(1): 0 / negative still take 1.
        assertEquals(1, GifSearchPolicy.filterAndSort(listOf(a, b), "cat", limit = 0).size)
        assertEquals(1, GifSearchPolicy.filterAndSort(listOf(a, b), "cat", limit = -3).size)
        assertEquals(2, GifSearchPolicy.filterAndSort(listOf(a, b), "cat", limit = 2).size)
        // Empty query matches everything; unknown recent ids are ignored.
        assertEquals(listOf("b", "a"), GifSearchPolicy.filterAndSort(listOf(a, b), "", listOf("b", "zzz")).map { it.id })
        assertEquals(emptyList<LocalGifItem>(), GifSearchPolicy.filterAndSort(listOf(a, b), "dog"))

        // pushRecent pinned
        assertEquals(listOf("c", "a", "b"), GifSearchPolicy.pushRecent(listOf("a", "b"), "c"))
        assertEquals(listOf("a", "b"), GifSearchPolicy.pushRecent(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), GifSearchPolicy.pushRecent(listOf("a", "b"), "  "))
        assertEquals(listOf("b", "a"), GifSearchPolicy.pushRecent(listOf("a", "b"), " b "))
        assertEquals(listOf("c"), GifSearchPolicy.pushRecent(listOf("a", "b"), "c", max = 1))
        assertEquals(listOf("c"), GifSearchPolicy.pushRecent(listOf("a", "b"), "c", max = 0))
        assertEquals(listOf("c"), GifSearchPolicy.pushRecent(listOf("a", "b"), "c", max = -2))
    }
}
