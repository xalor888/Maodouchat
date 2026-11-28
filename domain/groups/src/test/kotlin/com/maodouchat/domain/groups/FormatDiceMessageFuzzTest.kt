// Fuzz / random-oracle tests for GroupPlayPolicy.formatDiceMessage
// (checklist line 931, 126th block). Test-only, zero production changes.
// The oracle is hand-written: a blank roller name (probed 28-unit
// Kotlin isWhitespace set) drops the "who " prefix, otherwise the name
// is pasted verbatim -- never escaped, never trimmed.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDiceMessageFuzzTest {

// ---------- shared oracle ----------

// The probed Kotlin whitespace set (28 UTF-16 code units). Hand-written,
// never calls Char.isWhitespace / String.isBlank().
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun isBlankOracle(s: String): Boolean = s.all { it.code in BLANK_UNITS_ORACLE }

private fun formatDiceMessageOracle(value: Int, sides: Int, rollerName: String): String {
    val who = if (isBlankOracle(rollerName)) "" else "$rollerName "
    return "DICE:$sides:$value|${who}rolled $value / $sides"
}

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

private val DIC_VALUE_POOL = intArrayOf(
    0, 1, 2, 6, -1, -3, 7, 20, 100, Int.MIN_VALUE, Int.MAX_VALUE,
    Int.MIN_VALUE + 1, Int.MAX_VALUE - 1
)
private val DIC_SIDES_POOL = intArrayOf(
    0, 1, 2, 6, 20, 100, -1, Int.MIN_VALUE, Int.MAX_VALUE
)
private val ROLLER_POOL = arrayOf(
    "", " ", "   ", "\u00A0", "\u0085", "\u2003", "\u001C", "a", " a ",
    "alice", "x|y", "a\nb", "骰", "\uD800", "\uDC00", "a\uD800b"
)

private fun genDicValue(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) rnd.nextInt() else DIC_VALUE_POOL[rnd.nextInt(DIC_VALUE_POOL.size)]

private fun genDicSides(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) rnd.nextInt() else DIC_SIDES_POOL[rnd.nextInt(DIC_SIDES_POOL.size)]

private fun genRoller(rnd: Random): String =
    if (rnd.nextInt(3) == 0) randomText(rnd, 24) else ROLLER_POOL[rnd.nextInt(ROLLER_POOL.size)]

    @Test
    fun pinnedBoundaries() {
        // Literals verified by probe126 (WsProbe126.kt); U+0085 is not
        // blank so it keeps its "who " prefix, unlike U+00A0/U+2003.
        assertEquals("DICE:6:4|rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, ""))
        assertEquals("DICE:6:4|rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, " "))
        assertEquals("DICE:6:4|rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "   "))
        assertEquals("DICE:6:4|rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "\u00A0"))
        assertEquals("DICE:6:4|rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "\u2003"))
        assertEquals("DICE:6:4|rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "\u001C"))
        assertEquals("DICE:6:4|\u0085 rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "\u0085"))
        assertEquals("DICE:6:4|a rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "a"))
        assertEquals("DICE:6:4| a  rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, " a "))
        assertEquals("DICE:6:4|x|y rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "x|y"))
        assertEquals("DICE:6:4|a\nb rolled 4 / 6", GroupPlayPolicy.formatDiceMessage(4, 6, "a\nb"))
        assertEquals("DICE:6:2|rolled 2 / 6", GroupPlayPolicy.formatDiceMessage(2))
        assertEquals("DICE:6:-2147483648|rolled -2147483648 / 6", GroupPlayPolicy.formatDiceMessage(Int.MIN_VALUE, 6, ""))
        assertEquals("DICE:6:2147483647|rolled 2147483647 / 6", GroupPlayPolicy.formatDiceMessage(Int.MAX_VALUE, 6, ""))
        assertEquals("DICE:0:0|rolled 0 / 0", GroupPlayPolicy.formatDiceMessage(0, 0, ""))
        assertEquals("DICE:-1:1|rolled 1 / -1", GroupPlayPolicy.formatDiceMessage(1, -1, ""))
        assertEquals("DICE:1:7|rolled 7 / 1", GroupPlayPolicy.formatDiceMessage(7, 1, ""))
        assertEquals("DICE:100:-3|rolled -3 / 100", GroupPlayPolicy.formatDiceMessage(-3, 100, ""))
        assertEquals("DICE:2147483647:6|rolled 6 / 2147483647", GroupPlayPolicy.formatDiceMessage(6, Int.MAX_VALUE, ""))
        assertEquals("DICE:-2147483648:6|rolled 6 / -2147483648", GroupPlayPolicy.formatDiceMessage(6, Int.MIN_VALUE, ""))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(126002)
        repeat(1500) {
            val value = genDicValue(rnd)
            val sides = genDicSides(rnd)
            val roller = genRoller(rnd)
            assertEquals(
                formatDiceMessageOracle(value, sides, roller),
                GroupPlayPolicy.formatDiceMessage(value, sides, roller)
            )
        }
    }

    @Test
    fun poolExhaustive() {
        // Full cross product of the value/sides/roller pools, plus
        // every whitespace unit and every C0 control as a one-char
        // roller name.
        for (v in DIC_VALUE_POOL) {
            for (s in DIC_SIDES_POOL) {
                for (r in ROLLER_POOL) {
                    assertEquals(
                        formatDiceMessageOracle(v, s, r),
                        GroupPlayPolicy.formatDiceMessage(v, s, r)
                    )
                }
            }
        }
        for (u in BLANK_UNITS_ORACLE) {
            val r = u.toChar().toString()
            assertEquals(formatDiceMessageOracle(4, 6, r), GroupPlayPolicy.formatDiceMessage(4, 6, r))
        }
        for (u in 0x00..0x1F) {
            val r = u.toChar().toString()
            assertEquals(formatDiceMessageOracle(4, 6, r), GroupPlayPolicy.formatDiceMessage(4, 6, r))
        }
    }
}
