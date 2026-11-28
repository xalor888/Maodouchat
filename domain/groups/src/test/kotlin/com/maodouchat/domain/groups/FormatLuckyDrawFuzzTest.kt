// Fuzz / random-oracle tests for GroupPlayPolicy.formatLuckyDraw
// (checklist line 931, 126th block). Test-only, zero production changes.
// The oracle is hand-written: only the picker name is escaped
// ("|" -> U+0001, then "^" -> U+0002); the target name is pasted
// verbatim. Pre-existing U+0001/U+0002 pass through untouched.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatLuckyDrawFuzzTest {

// ---------- shared oracle ----------

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

private fun escOracle(s: String): String =
    s.replace("|", "\u0001").replace("^", "\u0002")

private fun formatLuckyDrawOracle(pickerName: String, targetName: String): String =
    "LUCKY:" + escOracle(pickerName) + "|" + targetName

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

private val LUK_NAME_POOL = arrayOf(
    "", "a", "picker", "a|b", "a^b", "|^", "a|b^c", "\u0001", "\u0002",
    "\u0001^\u0002|", "^^||", "x y", "a\nb", "张三", "\uD800", "\uDC00",
    "a\uD800b", "target|with|pipes", "a:b"
)

private fun genLukName(rnd: Random): String =
    if (rnd.nextInt(3) == 0) randomText(rnd, 24) else LUK_NAME_POOL[rnd.nextInt(LUK_NAME_POOL.size)]

    @Test
    fun pinnedBoundaries() {
        // Literals verified by probe126 (WsProbe126.kt). Note the
        // asymmetry: the picker is escaped, the target never is.
        assertEquals("LUCKY:a|b", GroupPlayPolicy.formatLuckyDraw("a", "b"))
        assertEquals("LUCKY:\u0001|x", GroupPlayPolicy.formatLuckyDraw("|", "x"))
        assertEquals("LUCKY:\u0002|x", GroupPlayPolicy.formatLuckyDraw("^", "x"))
        assertEquals("LUCKY:\u0001\u0002|x", GroupPlayPolicy.formatLuckyDraw("|^", "x"))
        assertEquals("LUCKY:a\u0001b\u0002c|t|u", GroupPlayPolicy.formatLuckyDraw("a|b^c", "t|u"))
        assertEquals("LUCKY:\u0001|t", GroupPlayPolicy.formatLuckyDraw("\u0001", "t"))
        assertEquals("LUCKY:\u0002|t", GroupPlayPolicy.formatLuckyDraw("\u0002", "t"))
        assertEquals("LUCKY:\u0001\u0002\u0002\u0001|t", GroupPlayPolicy.formatLuckyDraw("\u0001^\u0002|", "t"))
        assertEquals("LUCKY:|", GroupPlayPolicy.formatLuckyDraw("", ""))
        assertEquals("LUCKY:picker|", GroupPlayPolicy.formatLuckyDraw("picker", ""))
        assertEquals("LUCKY:p|a^b|c", GroupPlayPolicy.formatLuckyDraw("p", "a^b|c"))
        assertEquals("LUCKY:a\uD800b|t", GroupPlayPolicy.formatLuckyDraw("a\uD800b", "t"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(126003)
        repeat(1500) {
            val picker = genLukName(rnd)
            val target = genLukName(rnd)
            assertEquals(
                formatLuckyDrawOracle(picker, target),
                GroupPlayPolicy.formatLuckyDraw(picker, target)
            )
        }
    }

    @Test
    fun poolExhaustive() {
        // Full cross product of the name pool, single-unit picker
        // sweeps over the escape alphabet, and exhaustive short
        // pickers over {|, ^, a}.
        for (p in LUK_NAME_POOL) {
            for (t in LUK_NAME_POOL) {
                assertEquals(
                    formatLuckyDrawOracle(p, t),
                    GroupPlayPolicy.formatLuckyDraw(p, t)
                )
            }
        }
        for (u in listOf(0x7C, 0x5E, 0x01, 0x02, 0xD800, 0xDC00, 0x85, 0xA0, 0x20, 0x0A)) {
            for (t in listOf("", "x", "a|b", "a^b")) {
                val p = u.toChar().toString()
                assertEquals(formatLuckyDrawOracle(p, t), GroupPlayPolicy.formatLuckyDraw(p, t))
            }
        }
        for (ln in 0..3) {
            genShortPickers(ln, "", listOf("|", "^", "a"))
        }
    }

    // Recursive short-picker enumeration without itertools.
    private fun genShortPickers(ln: Int, prefix: String, alphabet: List<String>) {
        if (ln == 0) {
            assertEquals(formatLuckyDrawOracle(prefix, "t"), GroupPlayPolicy.formatLuckyDraw(prefix, "t"))
            return
        }
        for (ch in alphabet) genShortPickers(ln - 1, prefix + ch, alphabet)
    }
}
