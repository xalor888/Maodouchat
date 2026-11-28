// Fuzz / random-oracle tests for SignalDeviceIdRecoveryPolicy
// (checklist line 931, 131st block). Test-only, zero production changes.
// The oracle is hand-written from the production source: serverCode
// walks at most 4 cause levels and the first SignalExchangeException /
// ApiException frame wins even with a null code; the status predicates
// are trim() plus case-insensitive equality; shouldRetry is the
// attempt bound plus the conflict code; availableCandidate is a range
// plus set exclusion. No SignalDeviceIdRecoveryPolicy fuzz test existed
// before this block (content-grep dedup 0 hits on all 137 remote
// branches, main's 127 FuzzTest files, and all 134 open PR
// titles/bodies; the bare names `serverCode` / `shouldRetry` collide
// with unrelated helpers in other clusters' tests).
package com.maodouchat.core.crypto

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.IdentityRecoveryFailedException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

private fun chainOf(vararg frames: Pair<Char, String?>): Throwable? {
    var cause: Throwable? = null
    for ((kind, code) in frames.reversed()) {
        cause = when (kind) {
            'S' -> SignalExchangeException(SignalExchangeFailure.HTTP, serverCode = code, cause = cause)
            'A' -> ApiException(ApiFailureKind.HTTP, serverCode = code, cause = cause)
            'I' -> IdentityRecoveryFailedException(cause)
            else -> RuntimeException("x", cause)
        }
    }
    return cause
}

private fun sdirCodeOracle(chain: List<Pair<Char, String?>>): String? {
    var current = 0
    repeat(4) {
        if (current >= chain.size) return null
        val (kind, code) = chain[current]
        when (kind) {
            'S', 'A' -> return code
        }
        current++
    }
    return null
}

private fun equalsIgnoreCaseOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        val c1 = a[i]
        val c2 = b[i]
        if (c1 == c2) continue
        if (Character.toUpperCase(c1) == Character.toUpperCase(c2)) continue
        if (Character.toLowerCase(c1) == Character.toLowerCase(c2)) continue
        return false
    }
    return true
}

private fun sdirConfirmedOracle(status: String?): Boolean =
    status?.trim()?.let { equalsIgnoreCaseOracle(it, "CONFIRMED") } == true

private fun sdirPendingOracle(status: String?): Boolean =
    status?.trim()?.let { equalsIgnoreCaseOracle(it, "PENDING") } == true

private fun sdirRetryOracle(chain: List<Pair<Char, String?>>, attempt: Int, maxAttempts: Int): Boolean =
    attempt >= 0 && maxAttempts > 0 && attempt < maxAttempts - 1 &&
        sdirCodeOracle(chain) == "DEVICE_ID_CONFLICT"

private fun sdirCandOracle(
    candidate: Int,
    previousDeviceId: Int,
    occupiedDeviceIds: Set<Int>,
    attemptedDeviceIds: Set<Int>,
    minDeviceId: Int,
    maxDeviceId: Int
): Int? = candidate.takeIf {
    it in minDeviceId..maxDeviceId &&
        it != previousDeviceId &&
        it !in occupiedDeviceIds &&
        it !in attemptedDeviceIds
}

// ---------- generators (mirrored by Oracle131 GEN, seed 131003) ----------

private val SDIR_CODE_POOL = arrayOf<String?>(
    null, "DEVICE_ID_CONFLICT", "DEVICE_IDENTITY_MISMATCH", "DEVICE_SESSION_CONFLICT",
    "OTHER", "", " device_id_conflict ", "DEVICE_ID_CONFLICT ", "device_id_conflict"
)

private val SDIR_STATUS_POOL = arrayOf(
    "CONFIRMED", "confirmed", "Confirmed", "cOnFiRmEd", " confirmed",
    "confirmed ", " confirmed ", "CONFIRMED ", "CONFIRME",
    "CONFIRMEDX", "XCONFIRMED", "confirm", "CONFIRMED\n", "ＣＯＮＦＩＲＭＥＤ",
    "PENDING", "pending", "Pending", "pendıng", "PENDİNG", " pending ",
    "　pending　", "pendin", "PENDINGX", "PENDIN", "pending​",
    "PENDING\uD800", "", " ", "CONFIRMED\u0000", "pending\u0000"
)

private val SDIR_INT_POOL = intArrayOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, 2, 3, 254, 255, 256,
    Int.MAX_VALUE - 1, Int.MAX_VALUE
)

private val SDIR_SET_POOL = intArrayOf(0, 1, 2, 3, 4, 5, 255, 256, -1, 100)

private fun genSdirCode(rnd: Random): String? =
    if (rnd.nextInt(2) == 0) SDIR_CODE_POOL[rnd.nextInt(SDIR_CODE_POOL.size)]
    else randomText(rnd, 8)

private fun genSdirChain(rnd: Random): List<Pair<Char, String?>> {
    val n = rnd.nextInt(0, 7)
    return List(n) {
        val kind = "SAIO"[rnd.nextInt(4)]
        kind to genSdirCode(rnd)
    }
}

private fun genSdirStatus(rnd: Random): String =
    if (rnd.nextInt(2) == 0) SDIR_STATUS_POOL[rnd.nextInt(SDIR_STATUS_POOL.size)]
    else randomText(rnd, 10)

private fun genSdirStatusNullable(rnd: Random): String? =
    if (rnd.nextInt(6) == 0) null else genSdirStatus(rnd)

private fun genSdirInt(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) SDIR_INT_POOL[rnd.nextInt(SDIR_INT_POOL.size)]
    else rnd.nextInt()

private fun genSdirIntSet(rnd: Random): Set<Int> {
    val n = rnd.nextInt(0, 4)
    return (0 until n).map { SDIR_SET_POOL[rnd.nextInt(SDIR_SET_POOL.size)] }.toSet()
}

private fun genSdirMin(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) 2 else SDIR_SET_POOL[rnd.nextInt(SDIR_SET_POOL.size)]

private fun genSdirMax(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) 255 else SDIR_SET_POOL[rnd.nextInt(SDIR_SET_POOL.size)]

class SignalDeviceIdRecoveryPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // serverCode: null error, first S/A frame wins even with a null code.
        assertNull(SignalDeviceIdRecoveryPolicy.serverCode(null))
        assertEquals(
            "DEVICE_ID_CONFLICT",
            SignalDeviceIdRecoveryPolicy.serverCode(chainOf('S' to "DEVICE_ID_CONFLICT"))
        )
        assertEquals(
            "DEVICE_ID_CONFLICT",
            SignalDeviceIdRecoveryPolicy.serverCode(chainOf('A' to "DEVICE_ID_CONFLICT"))
        )
        assertNull(SignalDeviceIdRecoveryPolicy.serverCode(chainOf('S' to null)))
        assertNull(SignalDeviceIdRecoveryPolicy.serverCode(chainOf('A' to null)))
        assertNull(SignalDeviceIdRecoveryPolicy.serverCode(chainOf('I' to null)))
        assertNull(SignalDeviceIdRecoveryPolicy.serverCode(chainOf('O' to null)))
        assertEquals(
            "FIRST",
            SignalDeviceIdRecoveryPolicy.serverCode(chainOf('S' to "FIRST", 'A' to "SECOND"))
        )
        assertEquals(
            "FIRST",
            SignalDeviceIdRecoveryPolicy.serverCode(chainOf('A' to "FIRST", 'S' to "SECOND"))
        )
        // Non-code frames are skipped; only 4 levels are inspected.
        assertEquals(
            "DEVICE_IDENTITY_MISMATCH",
            SignalDeviceIdRecoveryPolicy.serverCode(chainOf('I' to null, 'S' to "DEVICE_IDENTITY_MISMATCH"))
        )
        assertEquals(
            "DEVICE_SESSION_CONFLICT",
            SignalDeviceIdRecoveryPolicy.serverCode(chainOf('O' to null, 'A' to "DEVICE_SESSION_CONFLICT"))
        )
        assertNull(
            SignalDeviceIdRecoveryPolicy.serverCode(
                chainOf('O' to null, 'O' to null, 'O' to null, 'O' to null, 'S' to "DEVICE_ID_CONFLICT")
            )
        )
        assertEquals(
            "DEVICE_ID_CONFLICT",
            SignalDeviceIdRecoveryPolicy.serverCode(
                chainOf('O' to null, 'O' to null, 'O' to null, 'S' to "DEVICE_ID_CONFLICT")
            )
        )
        // Status predicates: case-insensitive after trim; null is false.
        assertTrue(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("CONFIRMED"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("confirmed"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("cOnFiRmEd"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(" confirmed "))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(null))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("CONFIRME"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("CONFIRMEDX"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("ＣＯＮＦＩＲＭＥＤ"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("CONFIRMED\u0000"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("PENDING"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("pending"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(" pending "))
        // Dotless ı uppercases to I, so "pendıng" is PENDING; dotted İ
        // lowercases (single-char) to i, so "PENDİNG" is PENDING too.
        assertTrue(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("pendıng"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("PENDİNG"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(null))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("PENDIN"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("PENDINGX"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus("PENDING"))
        assertFalse(SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus("CONFIRMED"))
        // shouldRetry: attempt bound plus the conflict code.
        val conflict = chainOf('S' to "DEVICE_ID_CONFLICT")
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, 0, 5))
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, 3, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, 4, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, -1, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, 0, 1))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, 0, 0))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, 0, Int.MIN_VALUE))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(conflict, Int.MAX_VALUE, Int.MIN_VALUE))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(chainOf('S' to "OTHER"), 0, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(chainOf('A' to "DEVICE_ID_CONFLICT"), 1, 2))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetry(null, 0, 5))
        // availableCandidate: range, previous id, and both sets exclude.
        assertEquals(2, SignalDeviceIdRecoveryPolicy.availableCandidate(2, 1, emptySet(), emptySet()))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(1, 1, emptySet(), emptySet()))
        assertEquals(255, SignalDeviceIdRecoveryPolicy.availableCandidate(255, 1, emptySet(), emptySet()))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(256, 1, emptySet(), emptySet()))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(3, 3, emptySet(), emptySet()))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(3, 1, setOf(3), emptySet()))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(3, 1, emptySet(), setOf(3)))
        assertEquals(3, SignalDeviceIdRecoveryPolicy.availableCandidate(3, 1, setOf(2, 4), emptySet()))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(10, 1, emptySet(), emptySet(), 5, 8))
        assertEquals(5, SignalDeviceIdRecoveryPolicy.availableCandidate(5, 1, emptySet(), emptySet(), 5, 8))
        assertEquals(8, SignalDeviceIdRecoveryPolicy.availableCandidate(8, 1, emptySet(), emptySet(), 5, 8))
        assertNull(SignalDeviceIdRecoveryPolicy.availableCandidate(9, 1, emptySet(), emptySet(), 5, 8))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle131.genSdir consumption order exactly, so seed
        // 131003 replays the same 1500 GEN lines drive131.py ran.
        val rnd = Random(131003)
        repeat(1500) {
            when (rnd.nextInt(5)) {
                0 -> {
                    val chain = genSdirChain(rnd)
                    assertEquals(
                        sdirCodeOracle(chain),
                        SignalDeviceIdRecoveryPolicy.serverCode(chainOf(*chain.toTypedArray()))
                    )
                }
                1 -> {
                    val status = genSdirStatusNullable(rnd)
                    assertEquals(
                        sdirConfirmedOracle(status),
                        SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(status)
                    )
                }
                2 -> {
                    val status = genSdirStatusNullable(rnd)
                    assertEquals(
                        sdirPendingOracle(status),
                        SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(status)
                    )
                }
                3 -> {
                    val chain = genSdirChain(rnd)
                    val attempt = genSdirInt(rnd)
                    val max = genSdirInt(rnd)
                    assertEquals(
                        sdirRetryOracle(chain, attempt, max),
                        SignalDeviceIdRecoveryPolicy.shouldRetry(chainOf(*chain.toTypedArray()), attempt, max)
                    )
                }
                else -> {
                    val candidate = genSdirInt(rnd)
                    val previous = genSdirInt(rnd)
                    val occupied = genSdirIntSet(rnd)
                    val attempted = genSdirIntSet(rnd)
                    val mn = genSdirMin(rnd)
                    val mx = genSdirMax(rnd)
                    assertEquals(
                        sdirCandOracle(candidate, previous, occupied, attempted, mn, mx),
                        SignalDeviceIdRecoveryPolicy.availableCandidate(candidate, previous, occupied, attempted, mn, mx)
                    )
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool status (plus null) through both predicates.
        for (status in SDIR_STATUS_POOL.toList() + listOf<String?>(null)) {
            assertEquals(
                sdirConfirmedOracle(status),
                SignalDeviceIdRecoveryPolicy.isConfirmedDeviceStatus(status)
            )
            assertEquals(
                sdirPendingOracle(status),
                SignalDeviceIdRecoveryPolicy.isPendingDeviceStatus(status)
            )
        }
        // All chains of length 0..3 over kinds x codes.
        val chainCodes = arrayOf<String?>(null, "DEVICE_ID_CONFLICT", "DEVICE_IDENTITY_MISMATCH", "OTHER")
        val chains = ArrayList<List<Pair<Char, String?>>>()
        chains.add(emptyList())
        for (kind in "SAIO") {
            for (code in chainCodes) {
                chains.add(listOf(kind to code))
            }
        }
        for (k1 in "SAIO") {
            for (c1 in chainCodes) {
                for (k2 in "SAIO") {
                    for (c2 in chainCodes) {
                        chains.add(listOf(k1 to c1, k2 to c2))
                    }
                }
            }
        }
        for (k1 in "SAIO") {
            for (c1 in chainCodes) {
                for (k2 in "SAIO") {
                    for (c2 in chainCodes) {
                        for (k3 in "SAIO") {
                            for (c3 in chainCodes) {
                                chains.add(listOf(k1 to c1, k2 to c2, k3 to c3))
                            }
                        }
                    }
                }
            }
        }
        for (chain in chains) {
            assertEquals(
                sdirCodeOracle(chain),
                SignalDeviceIdRecoveryPolicy.serverCode(chainOf(*chain.toTypedArray()))
            )
        }
        // Retry grid over the short chains.
        val shortChains = chains.filter { it.size <= 2 }
        for (chain in shortChains) {
            for (attempt in arrayOf(-1, 0, 1, 2, 4, Int.MAX_VALUE, Int.MIN_VALUE)) {
                for (mx in arrayOf(-1, 0, 1, 2, 5, Int.MAX_VALUE, Int.MIN_VALUE)) {
                    assertEquals(
                        sdirRetryOracle(chain, attempt, mx),
                        SignalDeviceIdRecoveryPolicy.shouldRetry(chainOf(*chain.toTypedArray()), attempt, mx)
                    )
                }
            }
        }
        // Candidate grid.
        for (cand in arrayOf(-1, 0, 1, 2, 3, 5, 255, 256, Int.MAX_VALUE, Int.MIN_VALUE)) {
            for (prev in arrayOf(1, 3, 255)) {
                for (occ in listOf(emptySet<Int>(), setOf(3), setOf(2, 4), setOf(255))) {
                    for (att in listOf(emptySet<Int>(), setOf(3))) {
                        for ((mn, mx) in listOf(2 to 255, 5 to 8, 8 to 5, 0 to 0, 2 to 2, -1 to 300)) {
                            assertEquals(
                                sdirCandOracle(cand, prev, occ, att, mn, mx),
                                SignalDeviceIdRecoveryPolicy.availableCandidate(cand, prev, occ, att, mn, mx)
                            )
                        }
                    }
                }
            }
        }
    }
}
