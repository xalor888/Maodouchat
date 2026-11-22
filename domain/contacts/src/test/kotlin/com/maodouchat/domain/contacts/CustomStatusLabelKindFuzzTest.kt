package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [customStatusLabelKind] (checklist line 931,
 * 57th block). Test-only, zero production changes.
 *
 * The oracle re-implements the 24-preset table by hand (list lookup, not
 * the production when). Matching is exact: no trim, so a preset with one
 * extra space is Raw. Random inputs use Random(42), fresh per @Test.
 */
class CustomStatusLabelKindFuzzTest {

// ---------- C1 customStatusLabelKind oracle ----------

// The 24 preset wire values, in the same order as CustomStatusPolicy.
// PRESETS and CustomStatusLabelKind.entries; the oracle maps by list
// lookup, never by calling the production when.
private val PRESET_WIRES = listOf(
    "在线", "忙碌", "开会中", "请勿打扰", "马上回来", "休假中", "学习中",
    "通勤中", "专注中", "吃饭中", "旅游中", "运动中", "工作中", "通话中",
    "开车中", "游戏中", "睡觉中", "写作中", "出差中", "充电中", "听歌中",
    "阅读中", "观影中", "做饭中"
)

private val PRESET_KINDS = listOf(
    CustomStatusLabelKind.Online, CustomStatusLabelKind.Busy,
    CustomStatusLabelKind.Meeting, CustomStatusLabelKind.Dnd,
    CustomStatusLabelKind.Brb, CustomStatusLabelKind.Vacation,
    CustomStatusLabelKind.Studying, CustomStatusLabelKind.Commuting,
    CustomStatusLabelKind.Focusing, CustomStatusLabelKind.Eating,
    CustomStatusLabelKind.Traveling, CustomStatusLabelKind.Exercising,
    CustomStatusLabelKind.Working, CustomStatusLabelKind.OnCall,
    CustomStatusLabelKind.Driving, CustomStatusLabelKind.Gaming,
    CustomStatusLabelKind.Sleeping, CustomStatusLabelKind.Writing,
    CustomStatusLabelKind.BusinessTrip, CustomStatusLabelKind.Charging,
    CustomStatusLabelKind.Listening, CustomStatusLabelKind.Reading,
    CustomStatusLabelKind.Watching, CustomStatusLabelKind.Cooking
)

private fun kindOracle(wire: String): CustomStatusLabelKind {
    val idx = PRESET_WIRES.indexOf(wire)
    return if (idx >= 0) PRESET_KINDS[idx] else CustomStatusLabelKind.Raw
}

// Inputs cluster on the presets and on near-misses (one extra space /
// char, one char dropped): matching is exact, so every near-miss is Raw.
private fun randomWire(rnd: Random): String {
    return when (rnd.nextInt(6)) {
        0 -> PRESET_WIRES[rnd.nextInt(PRESET_WIRES.size)]
        1 -> {
            val preset = PRESET_WIRES[rnd.nextInt(PRESET_WIRES.size)]
            when (rnd.nextInt(6)) {
                0 -> " $preset"
                1 -> "$preset "
                2 -> "${preset}x"
                3 -> "x$preset"
                4 -> preset.dropLast(1)
                else -> "\u00A0$preset"
            }
        }
        2 -> ""
        else -> randomString(rnd, 24)
    }
}

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Digits Char.isDigit keeps (Nd), plus U+00B2 which is NOT a digit in
// Kotlin (unlike Python str.isdigit) — a deliberate discriminator.
private val DIGIT_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '\u0660', '\u06F0', '\u0966', '\uFF10', '\u00B2'
)

private val PLAIN_CHARS = charArrayOf(
    'a', 'Z', '0', '_', '-', '.', '@', 'é', '中', '\u200B', '\uFEFF',
    '\u0085', '\uD800', '|', ':', 'C', 'A', 'P', 'T', 'U', 'R', 'E', '/',
    '?', '%', ' '
)

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                // Astral char (emoji or astral digit U+104A0): 2 code units.
                // Its surrogates are not digits and not whitespace.
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

    @Test
    fun kindRandom() {
        val rnd = Random(42)
        repeat(300) {
            val wire = randomWire(rnd)
            assertEquals(kindOracle(wire), customStatusLabelKind(wire))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Every preset maps to its kind, and PRESETS / enum entries agree
        // in order (entries has one extra member at the end: Raw).
        for (i in PRESET_WIRES.indices) {
            assertEquals(PRESET_KINDS[i], customStatusLabelKind(PRESET_WIRES[i]))
            assertEquals(PRESET_WIRES[i], CustomStatusPolicy.PRESETS[i])
            assertEquals(PRESET_KINDS[i], CustomStatusLabelKind.entries[i])
        }
        assertEquals(24, CustomStatusPolicy.PRESETS.size)
        assertEquals(25, CustomStatusLabelKind.entries.size)
        assertEquals(CustomStatusLabelKind.Raw, CustomStatusLabelKind.entries[24])
        // Near-misses are all Raw: exact matching, no trim.
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind(""))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind(" "))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind(" 在线"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("在线 "))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("在线x"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("x在线"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("在"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("在线\n"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("\u00A0在线"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("忙碌 "))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("做饭中做饭中"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("ONLINE"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("\uD83D\uDE00"))
        // The first and last presets, pinned directly.
        assertEquals(CustomStatusLabelKind.Online, customStatusLabelKind("在线"))
        assertEquals(CustomStatusLabelKind.Cooking, customStatusLabelKind("做饭中"))
    }
}
