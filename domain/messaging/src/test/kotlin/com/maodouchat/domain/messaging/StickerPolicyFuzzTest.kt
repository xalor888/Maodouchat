// Fuzz / random-oracle tests for StickerPolicy (checklist line 931,
// 68th block). Test-only, zero production changes. The oracle is
// hand-written: trim over the probed 28-unit Kotlin whitespace set,
// per-code-point lowercase, unit-by-unit equality, hand-written
// contains / contains-ignore-case, hand-written take (UTF-16 units),
// explicit membership loops. pushRecent trims, dedupes, moves to front; normalizeEnabledPackIds filters to available and falls back to the whole available list when empty; enabledPacks keeps requested order; searchStickers dedupes in first-seen order; togglePackEnabled appends on enable and never disables the last pack.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class StickerPolicyFuzzTest {

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

// ---------- StickerPolicy oracle ----------

private fun stickerPushRecentOracle(existing: List<String>, sticker: String, max: Int): List<String> {
    val value = trimOracle(sticker)
    if (value.isEmpty()) return existing
    val out = mutableListOf(value)
    for (e in existing) {
        if (!equalsOracle(e, value)) out.add(e)
    }
    return out.take(coerceAtLeastOracle(max, 1))
}

private fun stickerNormalizeOracle(requested: List<String>?, availableIds: List<String>): List<String> {
    val filtered = mutableListOf<String>()
    for (raw in (requested ?: emptyList())) {
        val t = trimOracle(raw)
        if (t.isEmpty()) continue
        var inAvail = false
        for (a in availableIds) {
            if (equalsOracle(a, t)) { inAvail = true; break }
        }
        if (!inAvail) continue
        var dup = false
        for (f in filtered) {
            if (equalsOracle(f, t)) { dup = true; break }
        }
        if (!dup) filtered.add(t)
    }
    // Empty result falls back to the whole availableIds list verbatim
    // (order and duplicates included), not to an empty list.
    return if (filtered.isEmpty()) availableIds else filtered
}

private fun stickerEnabledPacksOracle(enabledIds: List<String>, catalog: List<StickerPack>): List<StickerPack> {
    val order = stickerNormalizeOracle(enabledIds, catalog.map { it.id })
    val out = mutableListOf<StickerPack>()
    for (id in order) {
        // associateBy: a duplicate pack id keeps the LAST pack.
        var found: StickerPack? = null
        for (pack in catalog) {
            if (equalsOracle(pack.id, id)) found = pack
        }
        if (found != null) out.add(found)
    }
    return out
}

private fun stickerSearchOracle(query: String, packs: List<StickerPack>): List<String> {
    val q = lowercaseOracle(trimOracle(query))
    if (q.isEmpty()) return emptyList()
    // linkedSetOf: first-seen order, duplicates dropped.
    val hits = mutableListOf<String>()
    fun addHit(s: String) {
        for (h in hits) {
            if (equalsOracle(h, s)) return
        }
        hits.add(s)
    }
    for (pack in packs) {
        for (sticker in pack.stickers) {
            if (containsOracle(sticker, q) || containsOracle(lowercaseOracle(sticker), q)) addHit(sticker)
        }
        for ((tag, stickers) in pack.emojiTags) {
            // `tag.contains(q) || q in tag` is one substring check twice.
            if (containsOracle(tag, q)) {
                for (s in stickers) addHit(s)
            } else {
                // Else branch: case-sensitive only, no lowercase arm.
                for (s in stickers) {
                    if (containsOracle(s, q)) addHit(s)
                }
            }
        }
    }
    return hits
}

private fun stickerToggleOracle(
    enabledIds: List<String>,
    packId: String,
    enable: Boolean,
    availableIds: List<String>
): List<String> {
    var inAvail = false
    for (a in availableIds) {
        if (equalsOracle(a, packId)) { inAvail = true; break }
    }
    if (!inAvail) return stickerNormalizeOracle(enabledIds, availableIds)
    val current = stickerNormalizeOracle(enabledIds, availableIds).toMutableList()
    if (enable) {
        var has = false
        for (c in current) {
            if (equalsOracle(c, packId)) { has = true; break }
        }
        if (!has) current.add(packId)
    } else {
        // At least one pack stays enabled.
        if (current.size > 1) {
            var idx = -1
            for (i in current.indices) {
                if (equalsOracle(current[i], packId)) { idx = i; break }
            }
            if (idx >= 0) current.removeAt(idx)
        }
    }
    return stickerNormalizeOracle(current, availableIds)
}

private val PACK_ID_POOL = arrayOf("a", "b", "c", "mood", "gesture", "party", "recent", "", " ", "A")

private fun genStickerPack(rnd: Random, tag: String): StickerPack {
    val id = PACK_ID_POOL[rnd.nextInt(PACK_ID_POOL.size)]
    val n = rnd.nextInt(6)
    val stickers = (0 until n).map { randomToken(rnd) }
    val tags = linkedMapOf<String, List<String>>()
    val tagPool = arrayOf("happy", "HAPPY", "party", "ok", "fire", "cat", "a b", "tag", "😀")
    repeat(rnd.nextInt(4)) {
        val key = tagPool[rnd.nextInt(tagPool.size)]
        val m = rnd.nextInt(4)
        tags[key] = (0 until m).map { randomToken(rnd) }
    }
    return StickerPack(id, tag + "_key", stickers, tags)
}

private fun genCatalog(rnd: Random): List<StickerPack> {
    val n = rnd.nextInt(5)
    return (0 until n).map { genStickerPack(rnd, "p$it") }
}

private fun genAvailableIds(rnd: Random): List<String> {
    val n = rnd.nextInt(5)
    return (0 until n).map { PACK_ID_POOL[rnd.nextInt(PACK_ID_POOL.size)] }
}

private fun genStickerPush(rnd: Random): Triple<List<String>, String, Int> {
    val n = rnd.nextInt(6)
    val existing = (0 until n).map { randomToken(rnd) }
    val sticker = when (rnd.nextInt(5)) {
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
    return Triple(existing, sticker, max)
}

private fun genStickerNormalize(rnd: Random): Pair<List<String>?, List<String>> {
    val requested: List<String>? = when (rnd.nextInt(6)) {
        0 -> null
        1 -> emptyList()
        else -> (0 until rnd.nextInt(6)).map {
            when (rnd.nextInt(4)) {
                0 -> " " + randomToken(rnd) + " "
                1 -> randomToken(rnd)
                2 -> PACK_ID_POOL[rnd.nextInt(PACK_ID_POOL.size)]
                else -> randomString(rnd, 6)
            }
        }
    }
    return requested to genAvailableIds(rnd)
}

private fun genStickerSearch(rnd: Random): Pair<String, List<StickerPack>> {
    val query = when (rnd.nextInt(6)) {
        0 -> ""
        1 -> "   "
        2 -> randomWord(rnd)
        3 -> randomWord(rnd).uppercase()
        4 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }
    return query to genCatalog(rnd)
}

private fun genStickerToggle(rnd: Random): ToggleInput {
    val available = genAvailableIds(rnd)
    val enabled = (0 until rnd.nextInt(5)).map { randomToken(rnd) }
    val packId = when (rnd.nextInt(4)) {
        0 -> if (available.isEmpty()) randomToken(rnd) else available[rnd.nextInt(available.size)]
        1 -> randomToken(rnd)
        2 -> PACK_ID_POOL[rnd.nextInt(PACK_ID_POOL.size)]
        else -> " " + randomToken(rnd) + " "
    }
    return ToggleInput(enabled, packId, rnd.nextBoolean(), available)
}

private data class ToggleInput(
    val enabledIds: List<String>,
    val packId: String,
    val enable: Boolean,
    val availableIds: List<String>
)

    @Test
    fun pushRecentRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (existing, sticker, max) = genStickerPush(rnd)
            assertEquals(stickerPushRecentOracle(existing, sticker, max), StickerPolicy.pushRecent(existing, sticker, max))
        }
    }

    @Test
    fun normalizeAndEnabledPacksRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (requested, available) = genStickerNormalize(rnd)
            assertEquals(
                stickerNormalizeOracle(requested, available),
                StickerPolicy.normalizeEnabledPackIds(requested, available)
            )
            val catalog = genCatalog(rnd)
            val enabled = (0 until rnd.nextInt(5)).map { randomToken(rnd) }
            assertEquals(
                stickerEnabledPacksOracle(enabled, catalog),
                StickerPolicy.enabledPacks(enabled, catalog)
            )
        }
    }

    @Test
    fun searchAndToggleRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (query, packs) = genStickerSearch(rnd)
            assertEquals(stickerSearchOracle(query, packs), StickerPolicy.searchStickers(query, packs))
            val input = genStickerToggle(rnd)
            assertEquals(
                stickerToggleOracle(input.enabledIds, input.packId, input.enable, input.availableIds),
                StickerPolicy.togglePackEnabled(input.enabledIds, input.packId, input.enable, input.availableIds)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // pushRecent pinned
        assertEquals(listOf("B", "A", "C"), StickerPolicy.pushRecent(listOf("A", "B", "C"), "B"))
        assertEquals(listOf("NEW"), StickerPolicy.pushRecent(listOf("A"), "NEW", max = 1))
        assertEquals(listOf("A"), StickerPolicy.pushRecent(listOf("A"), "  "))
        assertEquals(listOf("B", "A"), StickerPolicy.pushRecent(listOf("A", "B"), " B "))
        assertEquals(listOf("X"), StickerPolicy.pushRecent(listOf("A", "B"), "X", max = 0))

        // normalizeEnabledPackIds and enabledPacks pinned
        val available = listOf(StickerCatalog.PACK_MOOD, StickerCatalog.PACK_GESTURE)
        assertEquals(
            listOf(StickerCatalog.PACK_MOOD),
            StickerPolicy.normalizeEnabledPackIds(listOf(StickerCatalog.PACK_MOOD, "unknown"), available)
        )
        assertEquals(available, StickerPolicy.normalizeEnabledPackIds(emptyList(), available))
        assertEquals(available, StickerPolicy.normalizeEnabledPackIds(null, available))
        // Trim + case-sensitive membership + distinct.
        assertEquals(listOf("mood"), StickerPolicy.normalizeEnabledPackIds(listOf(" mood ", "MOOD", "mood"), listOf("mood")))
        assertEquals(listOf("a"), StickerPolicy.normalizeEnabledPackIds(listOf(" a ", ""), listOf("a", "b")))
        // Empty result falls back to availableIds verbatim, duplicates kept.
        assertEquals(listOf("a", "a", "b"), StickerPolicy.normalizeEnabledPackIds(emptyList(), listOf("a", "a", "b")))
        assertEquals(emptyList<String>(), StickerPolicy.normalizeEnabledPackIds(listOf("x"), emptyList()))
        // enabledPacks: requested order wins over catalog order.
        val packs = StickerPolicy.enabledPacks(listOf(StickerCatalog.PACK_PARTY, StickerCatalog.PACK_MOOD))
        assertEquals(listOf(StickerCatalog.PACK_PARTY, StickerCatalog.PACK_MOOD), packs.map { it.id })
        // Empty request falls back to the whole catalog, in catalog order.
        assertEquals(
            listOf(StickerCatalog.PACK_MOOD, StickerCatalog.PACK_GESTURE, StickerCatalog.PACK_PARTY),
            StickerPolicy.enabledPacks(emptyList()).map { it.id }
        )
        // Unknown ids are dropped.
        val custom = listOf(
            StickerPack("a", "a_key", listOf("😀")),
            StickerPack("b", "b_key", listOf("🎉"))
        )
        assertEquals(listOf("b", "a"), StickerPolicy.enabledPacks(listOf("b", "a", "zzz"), custom).map { it.id })
        // Duplicate pack ids: associateBy keeps the LAST pack.
        val dup = listOf(
            StickerPack("a", "first_key", listOf("😀")),
            StickerPack("a", "second_key", listOf("🎉"))
        )
        assertEquals(listOf("second_key"), StickerPolicy.enabledPacks(listOf("a"), dup).map { it.nameKey })

        // searchStickers and togglePackEnabled pinned
        // Empty / blank query returns empty, never the whole catalog.
        assertEquals(emptyList<String>(), StickerPolicy.searchStickers(""))
        assertEquals(emptyList<String>(), StickerPolicy.searchStickers("   "))
        // A tag hit adds the whole tag list, in tag order.
        val mood = StickerPack("mood", "mood_key", listOf("🥳", "🤗"), mapOf("happy" to listOf("🥳", "🤩", "😊")))
        assertEquals(listOf("🥳", "🤩", "😊"), StickerPolicy.searchStickers("happy", listOf(mood)))
        assertEquals(listOf("🥳", "🤩", "😊"), StickerPolicy.searchStickers("HAPPY", listOf(mood)))
        // Sticker itself matches, case-insensitively via the lowercase arm.
        val letters = StickerPack("letters", "letters_key", listOf("ABC"), mapOf("tag" to listOf("ABC")))
        assertEquals(listOf("ABC"), StickerPolicy.searchStickers("abc", listOf(letters)))
        // Else branch is case-sensitive only: tag misses, "ABC" stays out.
        val tagOnly = StickerPack("t", "t_key", listOf("x"), mapOf("tag" to listOf("ABC")))
        assertEquals(emptyList<String>(), StickerPolicy.searchStickers("abc", listOf(tagOnly)))
        // Tag hit adds even stickers that do not contain the query.
        val tagHit = StickerPack("t", "t_key", emptyList(), mapOf("hap" to listOf("AAA", "BBB")))
        assertEquals(listOf("AAA", "BBB"), StickerPolicy.searchStickers("hap", listOf(tagHit)))
        // Hits dedupe across packs, first-seen order.
        val p1 = StickerPack("p1", "p1_key", listOf("🔥", "x🔥"))
        val p2 = StickerPack("p2", "p2_key", listOf("🔥", "🔥y"))
        assertEquals(listOf("🔥", "x🔥", "🔥y"), StickerPolicy.searchStickers("🔥", listOf(p1, p2)))
        // İ: the query lowercases to i + combining dot, the sticker too.
        val dotted = StickerPack("d", "d_key", listOf("Lİ"))
        assertEquals(listOf("Lİ"), StickerPolicy.searchStickers("Lİ", listOf(dotted)))
        // togglePackEnabled: enable appends at the end.
        assertEquals(listOf("a", "b"), StickerPolicy.togglePackEnabled(listOf("a"), "b", true, listOf("a", "b", "c")))
        assertEquals(listOf("a", "b"), StickerPolicy.togglePackEnabled(listOf("a", "b"), "b", true, listOf("a", "b")))
        // Disable drops, but the last remaining pack stays.
        assertEquals(listOf("b"), StickerPolicy.togglePackEnabled(listOf("a", "b"), "a", false, listOf("a", "b")))
        assertEquals(listOf("a"), StickerPolicy.togglePackEnabled(listOf("a"), "a", false, listOf("a", "b")))
        // Unknown packId (or padded packId): normalized list, unchanged.
        assertEquals(listOf("a"), StickerPolicy.togglePackEnabled(listOf("a"), "zzz", true, listOf("a", "b")))
        assertEquals(listOf("a"), StickerPolicy.togglePackEnabled(listOf("a"), " b ", true, listOf("a", "b")))
        // Disabling a pack that is not enabled changes nothing.
        assertEquals(listOf("a"), StickerPolicy.togglePackEnabled(listOf("a"), "b", false, listOf("a", "b")))
        // Empty request normalizes to the fallback first, then enables.
        assertEquals(listOf("a", "b"), StickerPolicy.togglePackEnabled(emptyList(), "a", true, listOf("a", "b")))
    }
}
