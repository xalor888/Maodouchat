// Fuzz / random-oracle tests for messagingErrorCode
// (checklist line 931, 125th block). Test-only, zero production changes.
// The oracle is hand-written: take(120) on UTF-16 code units, then the
// probed 28-unit Kotlin isWhitespace set (probe125: isBlank() strips
// exactly those units -- U+0085 is NOT whitespace per Character.isWhitespace
// on JDK 17, lone surrogates are not blank, take(120) can split a pair).
// Fallback is the exception class simple name; an anonymous class has the
// empty simple name.
package com.maodouchat.domain.messaging

import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagingErrorCodeFuzzTest {

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

private fun isBlankOracle(s: String): Boolean = s.all { it.code in BLANK_UNITS_ORACLE }

private fun messagingErrorCodeOracle(message: String?, simpleName: String): String {
    val taken = message?.let { if (it.length <= 120) it else it.substring(0, 120) }
    if (taken != null && !isBlankOracle(taken)) return taken
    return simpleName
}

// ---------- GEN ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges for message bodies (surrogates excluded here; they get
// their own generator branch below so lone surrogates are reachable).
private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

// One random UTF-16 unit, with a small chance of a lone surrogate so the
// 120-unit cut can land inside a pair or on a lone unit.
private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            // Lone surrogate: high half the time, low the other half.
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private val LEN_POOL = intArrayOf(0, 1, 2, 3, 119, 120, 121, 122, 200, 300)

private fun genMessage(rnd: Random): String? {
    if (rnd.nextInt(6) == 0) return null
    val targetUnits = if (rnd.nextBoolean()) LEN_POOL[rnd.nextInt(LEN_POOL.size)] else rnd.nextInt(0, 301)
    val sb = StringBuilder()
    while (sb.length < targetUnits) randomUnit(rnd, sb)
    return sb.toString()
}

private fun genError(rnd: Random): Pair<Throwable, String> {
    val msg = genMessage(rnd)
    return when (rnd.nextInt(5)) {
        0 -> RuntimeException(msg) to "RuntimeException"
        1 -> IllegalArgumentException(msg) to "IllegalArgumentException"
        2 -> IllegalStateException(msg) to "IllegalStateException"
        3 -> IOException(msg) to "IOException"
        else -> NullPointerException(msg) to "NullPointerException"
    }
}

// ---------- tests ----------

@Test
fun pinnedBoundaries() {
    assertEquals("IllegalStateException", messagingErrorCode(IllegalStateException("")))
    assertEquals("IllegalStateException", messagingErrorCode(IllegalStateException("   ")))
    // Every probed whitespace unit, joined: still blank, still falls back.
    val allWs = WS_CHARS.concatToString()
    assertEquals("IllegalStateException", messagingErrorCode(IllegalStateException(allWs)))
    // U+0085 is NOT whitespace per Character.isWhitespace: it passes through.
    assertEquals("\u0085", messagingErrorCode(IllegalStateException("\u0085")))
    // Lone high surrogate is not blank either.
    assertEquals("\uD800", messagingErrorCode(IllegalStateException("\uD800")))
    assertEquals("x".repeat(120), messagingErrorCode(IllegalStateException("x".repeat(120))))
    assertEquals("x".repeat(120), messagingErrorCode(IllegalStateException("x".repeat(121))))
    // 119 units + one astral char: take(120) splits the pair, length stays 120.
    val split = "a".repeat(119) + String(Character.toChars(0x10400))
    val got = messagingErrorCode(IllegalStateException(split))
    assertEquals(120, got.length)
    assertEquals(0xD801, got.last().code)
    assertEquals(got, messagingErrorCodeOracle(split, "IllegalStateException"))
    // Null message falls back; anonymous class simple name is empty.
    assertEquals("IllegalArgumentException", messagingErrorCode(IllegalArgumentException()))
    assertEquals("", messagingErrorCode(object : RuntimeException("   ") {}))
    assertEquals("boom", messagingErrorCode(IllegalStateException("boom")))
}

@Test
fun randomOracleMatchesProduction() {
    val rnd = Random(125001)
    repeat(1500) {
        val (error, simple) = genError(rnd)
        assertEquals(
            messagingErrorCodeOracle(error.message, simple),
            messagingErrorCode(error)
        )
    }
}

@Test
fun lengthAndWhitespaceSweep() {
    // Every length 0..130 of 'a', two classes: the 120 cut, both sides.
    for (len in 0..130) {
        val msg = "a".repeat(len)
        assertEquals(messagingErrorCodeOracle(msg, "IllegalStateException"), messagingErrorCode(IllegalStateException(msg)))
        assertEquals(messagingErrorCodeOracle(msg, "RuntimeException"), messagingErrorCode(RuntimeException(msg)))
    }
    // Every single control / whitespace unit as the whole message.
    for (unit in 0x00..0x2F) {
        val msg = unit.toChar().toString()
        assertEquals(messagingErrorCodeOracle(msg, "IllegalStateException"), messagingErrorCode(IllegalStateException(msg)))
    }
    for (ws in WS_CHARS) {
        val msg = ws.toString()
        assertEquals(messagingErrorCodeOracle(msg, "IllegalStateException"), messagingErrorCode(IllegalStateException(msg)))
    }
    // Astral split at the boundary, several code points.
    for (cp in intArrayOf(0x10000, 0x10400, 0x1F600, 0x20000, 0x10FFFF)) {
        val msg = "a".repeat(119) + String(Character.toChars(cp))
        assertEquals(messagingErrorCodeOracle(msg, "IllegalStateException"), messagingErrorCode(IllegalStateException(msg)))
        val msg2 = "a".repeat(118) + String(Character.toChars(cp)) + "b"
        assertEquals(messagingErrorCodeOracle(msg2, "IllegalStateException"), messagingErrorCode(IllegalStateException(msg2)))
    }
}
}
