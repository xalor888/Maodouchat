package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for AgentPostReadLimitPolicy (checklist line
// 931, 62nd block). Test-only, zero production changes. The clamp oracle
// is hand-written (never coerceIn); random ints cluster on the bounds and
// the extremes.
class AgentPostReadLimitPolicyFuzzTest {

// ---------- C1 AgentPostReadLimitPolicy oracle ----------

// Hand-written clamp: below the range takes the low end, above takes
// the high end, inside passes through. Never calls coerceIn.
private fun clampOracle(v: Int, lo: Int, hi: Int): Int =
    if (v < lo) lo else if (v > hi) hi else v

private fun postsOracle(limit: Int): Int = clampOracle(limit, 1, 40)

private fun commentsOracle(limit: Int): Int = clampOracle(limit, 1, 100)

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended (with the
// multi-code-unit uppercase expansions), ligatures, fullwidth forms,
// Deseret / Adlam / Medefaidrin / Vithkuqi astral letters, CJK, combining
// marks, digits of several scripts, and general punctuation. Osage small
// letters are excluded: JDK 17 leaves them unchanged on uppercase while
// newer Unicode data maps them (pinned separately).
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0661 to 0x0661, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E61 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F610 to 0x1F610
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
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
            2 -> {
                // Lone surrogate (high or low): survives take/uppercase
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 42; 4 -> -42
    5 -> Int.MIN_VALUE; 6 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 42L; 4 -> -42L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

// ---------- block-62 generators ----------

// Overlay-mode raw value: null / empty / the three legal names / near
// misses (case, spacing, truncation, suffix) / random strings.
private val OVERLAY_NAMES = arrayOf("DISABLED", "FOREGROUND_ONLY", "ALWAYS")

private fun nearMissOverlayName(rnd: Random): String {
    val base = OVERLAY_NAMES[rnd.nextInt(OVERLAY_NAMES.size)]
    return when (rnd.nextInt(8)) {
        0 -> base.lowercase()
        1 -> base.replace("_", "")
        2 -> " $base"
        3 -> "$base "
        4 -> base.dropLast(1)
        5 -> base + "S"
        6 -> base.replaceFirstChar { it.lowercaseChar() }
        else -> base.drop(1)
    }
}

private fun randomOverlayRaw(rnd: Random): String? = when (rnd.nextInt(10)) {
    0 -> null
    1 -> ""
    2, 3, 4 -> OVERLAY_NAMES[rnd.nextInt(OVERLAY_NAMES.size)]
    5, 6 -> nearMissOverlayName(rnd)
    7 -> randomString(rnd, 24)
    8 -> OVERLAY_NAMES[rnd.nextInt(OVERLAY_NAMES.size)] + OVERLAY_NAMES[rnd.nextInt(OVERLAY_NAMES.size)]
    else -> randomString(rnd)
}

// Small id pool for blockedIds sets: short ids plus strings that stress
// set semantics (empty, whitespace, surrogate, astral, template-shaped).
private val ID_POOL = arrayOf(
    "", " ", "c1", "c2", "c3", "c4", "c5", "chat_1", "chat_2",
    "a", "b", "aa", "ab", "ba", "\ud800", "\udc00", "\ud83d\ude10",
    "\$x", "\${x}", "x\ud800y", "  padded  ", "\u000a", "0", "-1"
)

private fun randomIdSet(rnd: Random): Set<String> {
    val n = when (rnd.nextInt(6)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 5
        else -> rnd.nextInt(0, 8)
    }
    val out = mutableSetOf<String>()
    repeat(n) {
        out.add(if (rnd.nextInt(4) == 0) randomString(rnd, 12) else ID_POOL[rnd.nextInt(ID_POOL.size)])
    }
    return out
}

private fun randomBool(rnd: Random): Boolean = rnd.nextBoolean()

    @Test
    fun postsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val v = randomInt(rnd)
            assertEquals(postsOracle(v), AgentPostReadLimitPolicy.postsLimit(v))
        }
    }

    @Test
    fun commentsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val v = randomInt(rnd)
            assertEquals(commentsOracle(v), AgentPostReadLimitPolicy.commentsLimit(v))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // postsLimit: clamps to 1..40.
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(Int.MIN_VALUE))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(-42))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(-1))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(0))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(1))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(0))
        assertEquals(1, AgentPostReadLimitPolicy.postsLimit(1))
        assertEquals(2, AgentPostReadLimitPolicy.postsLimit(2))
        assertEquals(39, AgentPostReadLimitPolicy.postsLimit(39))
        assertEquals(40, AgentPostReadLimitPolicy.postsLimit(40))
        assertEquals(40, AgentPostReadLimitPolicy.postsLimit(41))
        assertEquals(40, AgentPostReadLimitPolicy.postsLimit(42))
        assertEquals(40, AgentPostReadLimitPolicy.postsLimit(Int.MAX_VALUE))
        // commentsLimit: clamps to 1..100.
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(Int.MIN_VALUE))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(-42))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(-1))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(0))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(1))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(0))
        assertEquals(1, AgentPostReadLimitPolicy.commentsLimit(1))
        assertEquals(2, AgentPostReadLimitPolicy.commentsLimit(2))
        assertEquals(99, AgentPostReadLimitPolicy.commentsLimit(99))
        assertEquals(100, AgentPostReadLimitPolicy.commentsLimit(100))
        assertEquals(100, AgentPostReadLimitPolicy.commentsLimit(101))
        assertEquals(42, AgentPostReadLimitPolicy.commentsLimit(42))
        assertEquals(100, AgentPostReadLimitPolicy.commentsLimit(Int.MAX_VALUE))    }
}
