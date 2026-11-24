// Fuzz / random-oracle tests for DecryptPath
// (checklist line 931, 134th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: exactly the
// string "SENDER_KEY" takes the group path, every other string takes
// the device path. Equality is exact: case, spacing, and any extra
// unit all matter. No DecryptPath fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

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

// ---------- oracle ----------

private fun dpDecideOracle(ciphertextType: String): DecryptPath =
    if (ciphertextType == SENDER_KEY_CIPHERTEXT_TYPE) DecryptPath.GROUP else DecryptPath.DEVICE

// ---------- generators (mirrored by Oracle134 GEN, seed 134002) ----------

private val DP_TYPE_POOL = arrayOf(
    "", " ", "SENDER_KEY", "sender_key", "Sender_Key", "SENDER-KEY",
    "SENDER_KEY ", " SENDER_KEY", "SENDER_KEYX", "XSENDER_KEY", "SENDER_KE",
    "SENDER_KEY\u0000", "TEXT", "SERVICE_PLAINTEXT", "SENDER", "KEY",
    "SENDER_KEY\uD800", "a\uD800b", "\uD83D\uDE00", "SENDER\u00A0KEY",
    "SENDER_KEY\n", "\u0000SENDER_KEY", "sender-key", "SENDER__KEY"
)

private fun genDpType(rnd: Random): String =
    if (rnd.nextInt(2) == 0) DP_TYPE_POOL[rnd.nextInt(DP_TYPE_POOL.size)]
    else randomText(rnd, 12)

class DecryptPathFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Only the exact constant takes the group path.
        assertEquals(DecryptPath.GROUP, decideDecryptPath("SENDER_KEY"))
        // Case, spacing, and affixes all miss.
        assertEquals(DecryptPath.DEVICE, decideDecryptPath(""))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath(" "))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("sender_key"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("Sender_Key"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER-KEY"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER_KEY "))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath(" SENDER_KEY"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER_KEYX"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("XSENDER_KEY"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER_KE"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER_KEY\u0000"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER__KEY"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER\u00A0KEY"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER_KEY\n"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("\u0000SENDER_KEY"))
        // The other known ciphertext types take the device path.
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("TEXT"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SERVICE_PLAINTEXT"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("KEY"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("sender-key"))
        // Lone surrogates and astral text are just units; they miss too.
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("SENDER_KEY\uD800"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("a\uD800b"))
        assertEquals(DecryptPath.DEVICE, decideDecryptPath("\uD83D\uDE00"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle134.genDp consumption order exactly, so seed
        // 134002 replays the same 1500 GEN lines drive134.py ran.
        val rnd = Random(134002)
        repeat(1500) {
            val ciphertextType = genDpType(rnd)
            assertEquals(dpDecideOracle(ciphertextType), decideDecryptPath(ciphertextType))
        }
    }

    @Test
    fun sweepExhaustive() {
        val target = "SENDER_KEY"
        // Every pool entry.
        for (t in DP_TYPE_POOL) {
            assertEquals(dpDecideOracle(t), decideDecryptPath(t))
        }
        // Every single low unit as the whole type: all miss.
        for (code in 0x00 until 0x100) {
            val s = code.toChar().toString()
            assertEquals(dpDecideOracle(s), decideDecryptPath(s))
        }
        // One-unit edits of the target: substitute, delete, insert, flip case.
        val edits = " _Xs0\u0000"
        for (pos in target.indices) {
            for (rep in edits) {
                val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                assertEquals(dpDecideOracle(s), decideDecryptPath(s))
            }
            val s = target.removeRange(pos, pos + 1)
            assertEquals(dpDecideOracle(s), decideDecryptPath(s))
            val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
            val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
            assertEquals(dpDecideOracle(f), decideDecryptPath(f))
        }
        for (pos in 0..target.length) {
            for (ins in listOf('_', 'X')) {
                val s = target.substring(0, pos) + ins + target.substring(pos)
                assertEquals(dpDecideOracle(s), decideDecryptPath(s))
            }
        }
        // Every two-unit string over the target's own alphabet: all miss.
        val alpha = "SENDER_KY"
        for (a in alpha) {
            for (b in alpha) {
                val s = "" + a + b
                assertEquals(dpDecideOracle(s), decideDecryptPath(s))
            }
        }
    }
}
