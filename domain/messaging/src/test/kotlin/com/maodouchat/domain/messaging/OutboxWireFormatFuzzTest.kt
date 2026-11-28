package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Probed full-table expansions: every code point whose uppercase()
// is not exactly one code point (102 entries, probed from real
// production over 0..0x10FFFF; single-code-point results all agree
// with Character.toUpperCase, so the rest goes through it).
internal val UPPER_EXPAND162 = mapOf(
    0x00DF to "SS", 0x0149 to "\u02BCN", 0x01F0 to "J\u030C", 0x0390 to "\u0399\u0308\u0301",
    0x03B0 to "\u03A5\u0308\u0301", 0x0587 to "\u0535\u0552", 0x1E96 to "H\u0331", 0x1E97 to "T\u0308",
    0x1E98 to "W\u030A", 0x1E99 to "Y\u030A", 0x1E9A to "A\u02BE", 0x1F50 to "\u03A5\u0313",
    0x1F52 to "\u03A5\u0313\u0300", 0x1F54 to "\u03A5\u0313\u0301", 0x1F56 to "\u03A5\u0313\u0342", 0x1F80 to "\u1F08\u0399",
    0x1F81 to "\u1F09\u0399", 0x1F82 to "\u1F0A\u0399", 0x1F83 to "\u1F0B\u0399", 0x1F84 to "\u1F0C\u0399",
    0x1F85 to "\u1F0D\u0399", 0x1F86 to "\u1F0E\u0399", 0x1F87 to "\u1F0F\u0399", 0x1F88 to "\u1F08\u0399",
    0x1F89 to "\u1F09\u0399", 0x1F8A to "\u1F0A\u0399", 0x1F8B to "\u1F0B\u0399", 0x1F8C to "\u1F0C\u0399",
    0x1F8D to "\u1F0D\u0399", 0x1F8E to "\u1F0E\u0399", 0x1F8F to "\u1F0F\u0399", 0x1F90 to "\u1F28\u0399",
    0x1F91 to "\u1F29\u0399", 0x1F92 to "\u1F2A\u0399", 0x1F93 to "\u1F2B\u0399", 0x1F94 to "\u1F2C\u0399",
    0x1F95 to "\u1F2D\u0399", 0x1F96 to "\u1F2E\u0399", 0x1F97 to "\u1F2F\u0399", 0x1F98 to "\u1F28\u0399",
    0x1F99 to "\u1F29\u0399", 0x1F9A to "\u1F2A\u0399", 0x1F9B to "\u1F2B\u0399", 0x1F9C to "\u1F2C\u0399",
    0x1F9D to "\u1F2D\u0399", 0x1F9E to "\u1F2E\u0399", 0x1F9F to "\u1F2F\u0399", 0x1FA0 to "\u1F68\u0399",
    0x1FA1 to "\u1F69\u0399", 0x1FA2 to "\u1F6A\u0399", 0x1FA3 to "\u1F6B\u0399", 0x1FA4 to "\u1F6C\u0399",
    0x1FA5 to "\u1F6D\u0399", 0x1FA6 to "\u1F6E\u0399", 0x1FA7 to "\u1F6F\u0399", 0x1FA8 to "\u1F68\u0399",
    0x1FA9 to "\u1F69\u0399", 0x1FAA to "\u1F6A\u0399", 0x1FAB to "\u1F6B\u0399", 0x1FAC to "\u1F6C\u0399",
    0x1FAD to "\u1F6D\u0399", 0x1FAE to "\u1F6E\u0399", 0x1FAF to "\u1F6F\u0399", 0x1FB2 to "\u1FBA\u0399",
    0x1FB3 to "\u0391\u0399", 0x1FB4 to "\u0386\u0399", 0x1FB6 to "\u0391\u0342", 0x1FB7 to "\u0391\u0342\u0399",
    0x1FBC to "\u0391\u0399", 0x1FC2 to "\u1FCA\u0399", 0x1FC3 to "\u0397\u0399", 0x1FC4 to "\u0389\u0399",
    0x1FC6 to "\u0397\u0342", 0x1FC7 to "\u0397\u0342\u0399", 0x1FCC to "\u0397\u0399", 0x1FD2 to "\u0399\u0308\u0300",
    0x1FD3 to "\u0399\u0308\u0301", 0x1FD6 to "\u0399\u0342", 0x1FD7 to "\u0399\u0308\u0342", 0x1FE2 to "\u03A5\u0308\u0300",
    0x1FE3 to "\u03A5\u0308\u0301", 0x1FE4 to "\u03A1\u0313", 0x1FE6 to "\u03A5\u0342", 0x1FE7 to "\u03A5\u0308\u0342",
    0x1FF2 to "\u1FFA\u0399", 0x1FF3 to "\u03A9\u0399", 0x1FF4 to "\u038F\u0399", 0x1FF6 to "\u03A9\u0342",
    0x1FF7 to "\u03A9\u0342\u0399", 0x1FFC to "\u03A9\u0399", 0xFB00 to "FF", 0xFB01 to "FI",
    0xFB02 to "FL", 0xFB03 to "FFI", 0xFB04 to "FFL", 0xFB05 to "ST",
    0xFB06 to "ST", 0xFB13 to "\u0544\u0546", 0xFB14 to "\u0544\u0535", 0xFB15 to "\u0544\u053B",
    0xFB16 to "\u054E\u0546", 0xFB17 to "\u0544\u053D",
)

internal fun ciphertextTypeOracle162(value: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        val mapped = UPPER_EXPAND162[cp]
        if (mapped != null) sb.append(mapped)
        else sb.appendCodePoint(Character.toUpperCase(cp))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// ---------- generators ----------

internal val UPPER_POOL162 = listOf(
    "", " ", "abc", "prekey", "signal", "PreKey", "SENDER_KEY",
    "\u00df", "\uFB00", "\uFB01", "\uFB02", "\uFB03", "\uFB04", "\uFB05", "\uFB06",
    "\u01F0", "\u0149", "\u0390", "\u03B0", "\u0587",
    "\uFB00\uFB00", "\u00df\u00df", "\u0130", "\u0131", "i", "I",
    "\u00b5", "\u017f", "\u03c2", "\u03a3", "\u03c3",
    "a\u0301", "i\u0307", "\uD83D\uDE00", "x\uD83D\uDE00y",
    "\uD800", "\uDFFF", "\uD800\uD800", "a\uD800b",
    "\uFB00a", "a\uFB00", "stra\u00dfe", "STRASSE",
)

internal val UPPER_ALPHABET162 =
    "abcxyzABCXYZ019 \t\n\u00e9\u4e2d\u00df\u0130\u0131\u03b1\u03c3\u03c2\u0300\u0301\u0307\u0308\u0342iI\u1e9e\uFB00\uFB01\uFB02\u01F0\u0149\uD83D\uDE00"

internal val UPPER_EXPAND_KEYS162 = UPPER_EXPAND162.keys.toList()

internal fun randomUpperChar162(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    2 -> String(Character.toChars(UPPER_EXPAND_KEYS162[rnd.nextInt(UPPER_EXPAND_KEYS162.size)]))
    else -> UPPER_ALPHABET162[rnd.nextInt(UPPER_ALPHABET162.length)].toString()
}

internal fun randomUpperString162(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 13)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < n) {
        val c = randomUpperChar162(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class UpperCase162(
    val value: String,
)

internal fun randomUpperCase162(rnd: Random): UpperCase162 {
    val value = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> UPPER_POOL162[rnd.nextInt(UPPER_POOL162.size)]
        else -> randomUpperString162(rnd)
    }
    return UpperCase162(value)
}

internal fun upperJsonEscape162(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun upperWireJson162(case: UpperCase162): String =
    "{\"value\":\"" + upperJsonEscape162(case.value) + "\"}"

class OutboxWireFormatFuzzTest {

    private fun check(value: String) {
        assertEquals(
            ciphertextTypeOracle162(value),
            OutboxWireFormat.ciphertextType(value),
        )
    }

    @Test
    fun pinnedBoundaries() {
        assertEquals("PREKEY", OutboxWireFormat.ciphertextType("prekey"))
        assertEquals("SIGNAL", OutboxWireFormat.ciphertextType("signal"))
        assertEquals("PREKEY", OutboxWireFormat.ciphertextType("PreKey"))
        assertEquals("SS", OutboxWireFormat.ciphertextType("\u00df"))
        assertEquals("FF", OutboxWireFormat.ciphertextType("\uFB00"))
        assertEquals("FI", OutboxWireFormat.ciphertextType("\uFB01"))
        assertEquals("FL", OutboxWireFormat.ciphertextType("\uFB02"))
        assertEquals("J\u030C", OutboxWireFormat.ciphertextType("\u01F0"))
        assertEquals("\u02BCN", OutboxWireFormat.ciphertextType("\u0149"))
        assertEquals("\u0399\u0308\u0301", OutboxWireFormat.ciphertextType("\u0390"))
        assertEquals("\u0535\u0552", OutboxWireFormat.ciphertextType("\u0587"))
        assertEquals("\u0130", OutboxWireFormat.ciphertextType("\u0130"))
        assertEquals("I", OutboxWireFormat.ciphertextType("\u0131"))
        assertEquals("\u039C", OutboxWireFormat.ciphertextType("\u00b5"))
        assertEquals("S", OutboxWireFormat.ciphertextType("\u017f"))
        assertEquals("\u03a3", OutboxWireFormat.ciphertextType("\u03c2"))
        assertEquals("STRASSE", OutboxWireFormat.ciphertextType("stra\u00dfe"))
        assertEquals("FFA", OutboxWireFormat.ciphertextType("\uFB00a"))
        assertEquals("AFF", OutboxWireFormat.ciphertextType("a\uFB00"))
        // Lone surrogates pass through untouched.
        assertEquals("\uD800", OutboxWireFormat.ciphertextType("\uD800"))
        assertEquals("A\uD800B", OutboxWireFormat.ciphertextType("a\uD800b"))
        assertEquals("X\uD83D\uDE00Y", OutboxWireFormat.ciphertextType("x\uD83D\uDE00y"))
        for (value in UPPER_POOL162) {
            check(value)
        }
        // Every probed expansion, on its own and flanked.
        for ((cp, mapped) in UPPER_EXPAND162) {
            val s = String(Character.toChars(cp))
            assertEquals(mapped, ciphertextTypeOracle162(s))
            assertEquals(mapped, OutboxWireFormat.ciphertextType(s))
            check("a" + s + "b")
            check(s + s)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(162002)
        repeat(2000) {
            check(randomUpperCase162(rnd).value)
        }
    }

    @Test
    fun sweepExhaustive() {
        for (value in UPPER_POOL162) {
            check(value)
        }
        for (c in UPPER_ALPHABET162) {
            check(c.toString())
        }
        // Every pair of alphabet chars.
        for (a in UPPER_ALPHABET162) {
            for (b in UPPER_ALPHABET162) {
                check(a.toString() + b)
            }
        }
        // Every code point, BMP + astral, as a one-char string.
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp !in 0xD800..0xDFFF) check(String(Character.toChars(cp)))
            cp++
        }
        // Every lone surrogate on its own and flanked.
        for (u in 0xD800..0xDFFF) {
            val s = u.toChar().toString()
            check(s)
            check("a" + s + "b")
        }
    }
}
