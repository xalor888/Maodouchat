// Fuzz / random-oracle tests for DecryptHistoryPolicy
// (checklist line 931, 128th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: keep is
// "everything but Success", defer is the NoSession/UntrustedIdentity
// pair, attempt is trim + empty check + set membership, new-device is
// just the negated flag. Res codes: 0 Success, 1 UnsupportedEnvelope,
// 2 NotForThisDevice, 3 NoSession, 4 UntrustedIdentity, 5 FutureEpoch,
// 6 Failed, 7 Duplicate. No DecryptHistoryPolicy fuzz test existed
// before this block.
package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- shared oracle ----------

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

private fun trimOracle(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].code in BLANK_UNITS_ORACLE) start++
    while (end > start && s[end - 1].code in BLANK_UNITS_ORACLE) end--
    return s.substring(start, end)
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

private fun dhpKeepOracle(res: Int): Boolean = res != 0

private fun dhpDeferOracle(res: Int): Boolean = res == 3 || res == 4

private fun dhpAttemptOracle(senderId: String, alreadyAttempted: Set<String>): Boolean {
    val id = trimOracle(senderId)
    if (id.isEmpty()) return false
    return id !in alreadyAttempted
}

private fun dhpNewDevOracle(identityRestoredFromStore: Boolean): Boolean = !identityRestoredFromStore

private val DHP_RES_POOL = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

private val DHP_PLAIN_POOL = arrayOf(
    "", " ", "hello", "plaintext", "a\uD800b", "\uDC00", "x|y",
    "  padded  ", "\u00A0", "a\nb", "secret", "0"
)

private val DHP_SENDER_POOL = arrayOf(
    "", " ", "   ", "\u00A0", "alice", "bob", " alice ", "alice ",
    " alice", "a", "u1", "u2", "a\uD800b", "\uDC00", "a\nb", "a\tb",
    "carol", "dave", "ALICE", "alice\u2003"
)

private fun genDhpRes(rnd: Random): Int = DHP_RES_POOL[rnd.nextInt(DHP_RES_POOL.size)]

private fun genDhpPlain(rnd: Random): String =
    if (rnd.nextInt(3) == 0) DHP_PLAIN_POOL[rnd.nextInt(DHP_PLAIN_POOL.size)] else randomText(rnd, 24)

private fun genDhpSender(rnd: Random): String =
    if (rnd.nextInt(3) == 0) DHP_SENDER_POOL[rnd.nextInt(DHP_SENDER_POOL.size)] else randomText(rnd, 12)

private fun genDhpAttempted(rnd: Random): List<String> {
    val n = rnd.nextInt(0, 5)
    val out = ArrayList<String>(n)
    repeat(n) { out.add(genDhpSender(rnd)) }
    return out
}

private fun resultFor(code: Int, plain: String): DecryptResult = when (code) {
    0 -> DecryptResult.Success(plain)
    1 -> DecryptResult.UnsupportedEnvelope
    2 -> DecryptResult.NotForThisDevice
    3 -> DecryptResult.NoSession
    4 -> DecryptResult.UntrustedIdentity
    5 -> DecryptResult.FutureEpoch
    6 -> DecryptResult.Failed
    else -> DecryptResult.Duplicate
}

class DecryptHistoryPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // keep: everything except a successful decrypt keeps the wire row.
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("hi")))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.UnsupportedEnvelope))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.NotForThisDevice))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.UntrustedIdentity))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.FutureEpoch))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Failed))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Duplicate))
        // defer: only NoSession and UntrustedIdentity are deferred.
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Success("hi")))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.FutureEpoch))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Failed))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Duplicate))
        // attempt: trim the id, blank ids never attempt, an id already
        // attempted is skipped. Only the sender is trimmed; the set
        // holds raw ids, so a padded sender matches a raw "alice"
        // entry but "alice" does not match a padded " alice " entry.
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("alice", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("alice", setOf("alice")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair(" alice ", setOf("alice")))
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("bob", setOf("alice")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("   ", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\u00A0", emptySet()))
        // The attempted set holds raw ids: "alice" is not in a set
        // containing only the padded " alice ".
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("alice", setOf(" alice ")))
        // new device: history cannot decrypt exactly when the identity
        // was NOT restored from the store.
        assertTrue(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(false))
        assertFalse(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle128.genDhp consumption order exactly, so seed
        // 128003 replays the same 1500 GEN lines drive128.py ran.
        val rnd = Random(128003)
        repeat(1500) {
            when (rnd.nextInt(4)) {
                0 -> {
                    val res = genDhpRes(rnd)
                    val plain = genDhpPlain(rnd)
                    assertEquals(dhpKeepOracle(res), DecryptHistoryPolicy.shouldKeepWire(resultFor(res, plain)))
                }
                1 -> {
                    val res = genDhpRes(rnd)
                    val plain = genDhpPlain(rnd)
                    assertEquals(dhpDeferOracle(res), DecryptHistoryPolicy.shouldDeferSessionRepair(resultFor(res, plain)))
                }
                2 -> {
                    val sender = genDhpSender(rnd)
                    val attempted = genDhpAttempted(rnd)
                    assertEquals(
                        dhpAttemptOracle(sender, attempted.toSet()),
                        DecryptHistoryPolicy.shouldAttemptSessionRepair(sender, attempted.toSet())
                    )
                }
                else -> {
                    val restored = rnd.nextBoolean()
                    assertEquals(dhpNewDevOracle(restored), DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(restored))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Res x plain for keep/defer, sender pool x attempted-set
        // universe for attempt, both flags for new-device -- the same
        // inputs drive128.py sweeps.
        for (res in DHP_RES_POOL) {
            for (plain in DHP_PLAIN_POOL) {
                assertEquals(dhpKeepOracle(res), DecryptHistoryPolicy.shouldKeepWire(resultFor(res, plain)))
                assertEquals(dhpDeferOracle(res), DecryptHistoryPolicy.shouldDeferSessionRepair(resultFor(res, plain)))
            }
        }
        val uniSenders = arrayOf("alice", " alice ", "", " ", "bob")
        for (sender in DHP_SENDER_POOL) {
            for (att in listOf(emptyList<String>()) + uniSenders.map { listOf(it) } +
                uniSenders.flatMap { a -> uniSenders.map { b -> listOf(a, b) } }) {
                assertEquals(
                    dhpAttemptOracle(sender, att.toSet()),
                    DecryptHistoryPolicy.shouldAttemptSessionRepair(sender, att.toSet())
                )
            }
        }
        for (restored in listOf(false, true)) {
            assertEquals(dhpNewDevOracle(restored), DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(restored))
        }
        // Blank/trim spot checks over the whitespace units.
        val wsUnits = arrayOf(
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
            0xA0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
            0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000
        )
        for (u in wsUnits) {
            val s = u.toChar().toString()
            assertEquals(isBlankOracle(s), s.isBlank())
            assertEquals(trimOracle(s), s.trim())
            assertEquals(isBlankOracle("alice$s"), "alice$s".isBlank())
            assertEquals(trimOracle("alice$s"), "alice$s".trim())
        }
        for (s in DHP_SENDER_POOL) {
            assertEquals(isBlankOracle(s), s.isBlank())
            assertEquals(trimOracle(s), s.trim())
        }
    }
}
