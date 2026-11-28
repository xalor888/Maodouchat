// Fuzz / random-oracle tests for decideOutboxClaimAction
// (checklist line 931, 136th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: exactly
// "PREPARING" prepares and "SENDING" sends; anything else is Invalid
// and echoes the state verbatim. Matching is exact: case, spacing, and
// any extra unit all miss. The oracle answers in plain codes
// ("PREPARE" / "SEND" / "INVALID:<state>") so it never touches the
// production sealed type.
// No OutboxClaimAction fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies -- the #2171 hits are
// the 135th block's next-round candidate list, not coverage).
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

private fun ocaOracle(state: String): String = when (state) {
    "PREPARING" -> "PREPARE"
    "SENDING" -> "SEND"
    else -> "INVALID:$state"
}

private fun ocaCode(action: OutboxClaimAction): String = when (action) {
    OutboxClaimAction.Prepare -> "PREPARE"
    OutboxClaimAction.Send -> "SEND"
    is OutboxClaimAction.Invalid -> "INVALID:${action.state}"
}

// ---------- generators (mirrored by Oracle136 GEN, seed 136002) ----------

private val OCA_STATE_POOL = arrayOf(
    "", " ", "PREPARING", "SENDING",
    "preparing", "sending", "Preparing", "Sending", "PREPARING ", " PREPARING",
    "SENDING ", " SENDING", "PREPARINGX", "XPREPARING", "SENDINGX", "XSENDING",
    "QUEUED", "RETRY_SEND", "PREPARIN", "SENDIN", "PREPAR", "SEND",
    "PREPARING\n", "SENDING\n", "PREPARING\u0000", "\u0000PREPARING",
    "a\uD800b", "\uD83D\uDE00", "PREPARING_PREPARING", "SENDING_SENDING"
)

private fun genOcaState(rnd: Random): String =
    if (rnd.nextInt(2) == 0) OCA_STATE_POOL[rnd.nextInt(OCA_STATE_POOL.size)]
    else randomText(rnd, 14)

class OutboxClaimActionFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Exactly the two claimed states dispatch; anything else echoes.
        assertEquals("PREPARE", ocaCode(decideOutboxClaimAction("PREPARING")))
        assertEquals("SEND", ocaCode(decideOutboxClaimAction("SENDING")))
        assertEquals("INVALID:QUEUED", ocaCode(decideOutboxClaimAction("QUEUED")))
        assertEquals("INVALID:RETRY_SEND", ocaCode(decideOutboxClaimAction("RETRY_SEND")))
        assertEquals("INVALID:", ocaCode(decideOutboxClaimAction("")))
        assertEquals("INVALID: ", ocaCode(decideOutboxClaimAction(" ")))
        // Matching is exact: case, spacing, and affixes all miss.
        assertEquals("INVALID:preparing", ocaCode(decideOutboxClaimAction("preparing")))
        assertEquals("INVALID:sending", ocaCode(decideOutboxClaimAction("sending")))
        assertEquals("INVALID:Preparing", ocaCode(decideOutboxClaimAction("Preparing")))
        assertEquals("INVALID:PREPARING ", ocaCode(decideOutboxClaimAction("PREPARING ")))
        assertEquals("INVALID: PREPARING", ocaCode(decideOutboxClaimAction(" PREPARING")))
        assertEquals("INVALID:SENDING ", ocaCode(decideOutboxClaimAction("SENDING ")))
        assertEquals("INVALID: SENDING", ocaCode(decideOutboxClaimAction(" SENDING")))
        assertEquals("INVALID:PREPARINGX", ocaCode(decideOutboxClaimAction("PREPARINGX")))
        assertEquals("INVALID:XPREPARING", ocaCode(decideOutboxClaimAction("XPREPARING")))
        assertEquals("INVALID:SENDINGX", ocaCode(decideOutboxClaimAction("SENDINGX")))
        assertEquals("INVALID:XSENDING", ocaCode(decideOutboxClaimAction("XSENDING")))
        assertEquals("INVALID:PREPARIN", ocaCode(decideOutboxClaimAction("PREPARIN")))
        assertEquals("INVALID:SENDIN", ocaCode(decideOutboxClaimAction("SENDIN")))
        assertEquals("INVALID:PREPARING\n", ocaCode(decideOutboxClaimAction("PREPARING\n")))
        assertEquals("INVALID:PREPARING\u0000", ocaCode(decideOutboxClaimAction("PREPARING\u0000")))
        assertEquals("INVALID:\u0000PREPARING", ocaCode(decideOutboxClaimAction("\u0000PREPARING")))
        // Lone surrogates and astral text echo verbatim in Invalid.
        assertEquals("INVALID:a\uD800b", ocaCode(decideOutboxClaimAction("a\uD800b")))
        assertEquals("INVALID:\uD83D\uDE00", ocaCode(decideOutboxClaimAction("\uD83D\uDE00")))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle136.genOca consumption order exactly, so seed
        // 136002 replays the same 1500 GEN lines drive136.py ran.
        val rnd = Random(136002)
        repeat(1500) {
            val state = genOcaState(rnd)
            assertEquals(ocaOracle(state), ocaCode(decideOutboxClaimAction(state)))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool state.
        for (state in OCA_STATE_POOL) {
            assertEquals(ocaOracle(state), ocaCode(decideOutboxClaimAction(state)))
        }
        // One-unit edits of both claimed states: substitute, delete,
        // insert, flip case.
        val edits = " _Xa0\u0000"
        for (target in listOf("PREPARING", "SENDING")) {
            for (pos in target.indices) {
                for (rep in edits) {
                    val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                    assertEquals(ocaOracle(s), ocaCode(decideOutboxClaimAction(s)))
                }
                val s = target.removeRange(pos, pos + 1)
                assertEquals(ocaOracle(s), ocaCode(decideOutboxClaimAction(s)))
                val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
                val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
                assertEquals(ocaOracle(f), ocaCode(decideOutboxClaimAction(f)))
            }
            for (pos in 0..target.length) {
                for (ins in listOf('_', 'X')) {
                    val s = target.substring(0, pos) + ins + target.substring(pos)
                    assertEquals(ocaOracle(s), ocaCode(decideOutboxClaimAction(s)))
                }
            }
        }
        // Every single low unit as the whole state.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            assertEquals(ocaOracle(s), ocaCode(decideOutboxClaimAction(s)))
        }
    }
}
