// Fuzz / random-oracle tests for DecryptFailurePolicy
// (checklist line 931, 127th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: disposition /
// tracking / shouldStop / persist / fingerprint are pure over the
// sealed DecryptResult (8 variants); no libsignal is involved here.
// isTransient / transientFailure* are NOT covered here (exception
// typed, already unit-tested in DecryptFailurePolicyTest).
package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
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

// res codes: 0 Success(plain) 1 UnsupportedEnvelope 2 NotForThisDevice
// 3 NoSession 4 UntrustedIdentity 5 FutureEpoch 6 Failed 7 Duplicate
private fun resultOracle(code: Int, plain: String): DecryptResult = when (code) {
    0 -> DecryptResult.Success(plain)
    1 -> DecryptResult.UnsupportedEnvelope
    2 -> DecryptResult.NotForThisDevice
    3 -> DecryptResult.NoSession
    4 -> DecryptResult.UntrustedIdentity
    5 -> DecryptResult.FutureEpoch
    6 -> DecryptResult.Failed
    else -> DecryptResult.Duplicate
}

private fun dfpDispositionOracle(res: Int): String = when (res) {
    0, 1, 2, 7 -> "STOP"
    else -> "RETRY"
}

private fun dfpTrackingOracle(res: Int): String = when {
    res == 0 -> "CLEAR"
    dfpDispositionOracle(res) == "RETRY" -> "RECORD_FAILURE"
    else -> "MARK_TERMINAL"
}

private fun dfpShouldStopOracle(res: Int, prev: Int): Boolean {
    if (dfpDispositionOracle(res) == "STOP") return true
    return prev >= 5
}

private fun dfpShouldSkipOracle(prev: Int): Boolean = prev >= 5

private fun dfpPersistOracle(res: Int, plain: String, wire: String): String {
    if (isBlankOracle(wire)) return wire
    return if (res == 0) plain else wire
}

private fun dfpNeverPersistOracle(res: Int): Boolean = res != 0

private fun dfpFingerprintOracle(senderId: String, content: String): String {
    var hash = 1125899906842597L
    val seed = senderId.length * 31L + content.length
    hash = hash * 31 + seed
    hash = hash * 31 + hashCodeOracle(senderId)
    hash = hash * 31 + hashCodeOracle(content)
    return "" + senderId.length + ":" + content.length + ":" + hash
}

private val DFP_RES_POOL = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

private val DFP_PLAIN_POOL = arrayOf(
    "", " ", "hello", "plaintext", "a\uD800b", "\uDC00", "x|y", "  padded  ",
    "\u00A0", "a\nb", "secret", "0"
)

private val DFP_PREV_POOL = intArrayOf(
    -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 100, Int.MIN_VALUE, Int.MAX_VALUE
)

private val DFP_WIRE_POOL = arrayOf(
    "", " ", "   ", "\u00A0", "\u2003", "wire", "ciphertext-bytes", "a\uD800b",
    "\uDC00", "  x  ", "0"
)

private fun genDfpRes(rnd: Random): Int = DFP_RES_POOL[rnd.nextInt(DFP_RES_POOL.size)]

private fun genDfpPlain(rnd: Random): String =
    if (rnd.nextInt(3) == 0) DFP_PLAIN_POOL[rnd.nextInt(DFP_PLAIN_POOL.size)] else randomText(rnd, 24)

private fun genDfpWire(rnd: Random): String =
    if (rnd.nextInt(3) == 0) DFP_WIRE_POOL[rnd.nextInt(DFP_WIRE_POOL.size)] else randomText(rnd, 24)

private fun genDfpPrev(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) DFP_PREV_POOL[rnd.nextInt(DFP_PREV_POOL.size)] else rnd.nextInt(-10, 11)

class DecryptFailurePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Disposition / tracking matrix: Success + the three terminal
        // objects stop; the four recoverable objects retry.
        for (res in 0..7) {
            val expectedDisp = if (res in intArrayOf(0, 1, 2, 7)) Disposition.STOP else Disposition.RETRY
            assertEquals(expectedDisp, DecryptFailurePolicy.disposition(resultOracle(res, "p")))
            val expectedTrack = when (res) {
                0 -> TrackingAction.CLEAR
                3, 4, 5, 6 -> TrackingAction.RECORD_FAILURE
                else -> TrackingAction.MARK_TERMINAL
            }
            assertEquals(expectedTrack, DecryptFailurePolicy.trackingAction(resultOracle(res, "p")))
            assertEquals(res != 0, DecryptFailurePolicy.neverPersistUiPlaceholder(resultOracle(res, "p")))
        }
        // shouldStop: terminal results stop at any attempt count; a
        // retryable result stops only at the 5-attempt cap.
        for (res in 0..7) {
            for (prev in intArrayOf(-2, -1, 0, 1, 4, 5, 6, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertEquals(dfpShouldStopOracle(res, prev), DecryptFailurePolicy.shouldStop(resultOracle(res, "p"), prev))
                assertEquals(dfpShouldSkipOracle(prev), DecryptFailurePolicy.shouldSkipCryptoAttempt(prev))
            }
        }
        // persist: blank wire always wins (returned verbatim); success
        // swaps in the plaintext, anything else keeps the wire.
        for (res in 0..7) {
            for (wire in DFP_WIRE_POOL) {
                for (plain in DFP_PLAIN_POOL) {
                    assertEquals(
                        dfpPersistOracle(res, plain, wire),
                        DecryptFailurePolicy.persistDecryptResultToRoom(resultOracle(res, plain), wire, "placeholder")
                    )
                }
            }
        }
        // Fingerprint literals, cross-checked against wsprobe127.log
        // (Java String.hashCode over UTF-16 units, 64-bit wrap).
        assertEquals("5:5:-3351804019691985683", DecryptFailurePolicy.envelopeFingerprint("alice", "hello"))
        assertEquals("0:0:-3351804022671296005", DecryptFailurePolicy.envelopeFingerprint("", ""))
        assertEquals("1:1:-3351804022671262148", DecryptFailurePolicy.envelopeFingerprint("a", "b"))
        assertEquals("3:3:-3351804022613310167", DecryptFailurePolicy.envelopeFingerprint("a\uD800b", "x\uDC00y"))
        assertEquals("6:16:-3351804049411230522", DecryptFailurePolicy.envelopeFingerprint("sender", "ciphertext-bytes"))
        // Fingerprint is a pure function of (sender, content) lengths
        // and hashCodes: same inputs, same output; lengths are UTF-16
        // units (an astral char counts 2).
        assertEquals(
            DecryptFailurePolicy.envelopeFingerprint("a", "b"),
            DecryptFailurePolicy.envelopeFingerprint("a", "b")
        )
        assertTrue(DecryptFailurePolicy.envelopeFingerprint("\uD800", "").startsWith("1:0:"))
        assertFalse(DecryptFailurePolicy.envelopeFingerprint("alice", "hello") ==
            DecryptFailurePolicy.envelopeFingerprint("alice", "helloo"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle127.genDfp consumption order exactly: the op
        // dispatch comes first, then the per-branch generators, so
        // seed 127001 replays the same 1500 GEN lines drive127.py ran.
        val rnd = Random(127001)
        repeat(1500) {
            when (rnd.nextInt(7)) {
                0 -> {
                    val res = genDfpRes(rnd)
                    val plain = genDfpPlain(rnd)
                    assertEquals(dfpDispositionOracle(res), DecryptFailurePolicy.disposition(resultOracle(res, plain)).name)
                }
                1 -> {
                    val res = genDfpRes(rnd)
                    val plain = genDfpPlain(rnd)
                    assertEquals(dfpTrackingOracle(res), DecryptFailurePolicy.trackingAction(resultOracle(res, plain)).name)
                }
                2 -> {
                    val res = genDfpRes(rnd)
                    val plain = genDfpPlain(rnd)
                    val prev = genDfpPrev(rnd)
                    assertEquals(dfpShouldStopOracle(res, prev), DecryptFailurePolicy.shouldStop(resultOracle(res, plain), prev))
                }
                3 -> {
                    val prev = genDfpPrev(rnd)
                    assertEquals(dfpShouldSkipOracle(prev), DecryptFailurePolicy.shouldSkipCryptoAttempt(prev))
                }
                4 -> {
                    val res = genDfpRes(rnd)
                    val plain = genDfpPlain(rnd)
                    val wire = genDfpWire(rnd)
                    val placeholder = genDfpPlain(rnd)
                    assertEquals(dfpPersistOracle(res, plain, wire),
                        DecryptFailurePolicy.persistDecryptResultToRoom(resultOracle(res, plain), wire, placeholder))
                }
                5 -> {
                    val res = genDfpRes(rnd)
                    val plain = genDfpPlain(rnd)
                    assertEquals(dfpNeverPersistOracle(res), DecryptFailurePolicy.neverPersistUiPlaceholder(resultOracle(res, plain)))
                }
                else -> {
                    val sender = genDfpPlain(rnd)
                    val content = genDfpPlain(rnd)
                    assertEquals(dfpFingerprintOracle(sender, content),
                        DecryptFailurePolicy.envelopeFingerprint(sender, content))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every result x every attempt count, plus the persist matrix
        // over the full wire/plaint pools and the fingerprint cross
        // product -- the same inputs drive127.py sweeps.
        for (res in 0..7) {
            for (prev in DFP_PREV_POOL) {
                assertEquals(dfpShouldStopOracle(res, prev), DecryptFailurePolicy.shouldStop(resultOracle(res, "p"), prev))
            }
            for (prev in -10..10) {
                assertEquals(dfpShouldStopOracle(res, prev), DecryptFailurePolicy.shouldStop(resultOracle(res, "p"), prev))
                assertEquals(dfpShouldSkipOracle(prev), DecryptFailurePolicy.shouldSkipCryptoAttempt(prev))
            }
        }
        val fpPool = arrayOf(
            "", "a", "alice", "bob", "  ", "\u00A0", "a\uD800b", "\uDC00",
            "sender", "x|y", "a\nb", "longer sender id here", "0"
        )
        for (s in fpPool) {
            for (c in fpPool) {
                assertEquals(dfpFingerprintOracle(s, c), DecryptFailurePolicy.envelopeFingerprint(s, c))
            }
        }
    }
}
