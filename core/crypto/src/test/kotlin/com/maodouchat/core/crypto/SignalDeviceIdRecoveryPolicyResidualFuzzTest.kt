// SignalDeviceIdRecoveryPolicy residual fuzz (148th block). Covers the
// six entry points fuzz-131 left out (invalidatesLocalCrypto,
// isMigrationPendingMarkerPresent, mayReallocateDeviceId x2,
// shouldReallocateForIdentityMismatch, shouldRetryMigrationCandidate);
// serverCode and the other five functions are already fuzz-covered by
// fuzz-131, so the oracle below reimplements serverCode the same way
// fuzz-131 did and this block only asserts the residual functions.
package com.maodouchat.core.crypto

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.IdentityRecoveryFailedException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- shared random-text helpers (same shape as fuzz-131) ----------

private val WS_CHARS148 = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES148 = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint148(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES148[rnd.nextInt(TEXT_RANGES148.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit148(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS148[rnd.nextInt(WS_CHARS148.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint148(rnd)))
    }
}

private fun sdirRandomText148(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit148(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun chainOf148(vararg frames: Pair<Char, String?>): Throwable? {
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

// Same walk as fuzz-131's sdirCodeOracle: at most 4 cause levels, the
// first SignalExchangeException / ApiException frame wins, even with a
// null code (null then propagates as the answer).
private fun sdirCodeOracle148(chain: List<Pair<Char, String?>>): String? {
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

private fun sdirInvalidatesOracle148(chain: List<Pair<Char, String?>>): Boolean {
    if (chain.firstOrNull()?.first == 'I') return true
    return sdirCodeOracle148(chain) in setOf("DEVICE_IDENTITY_MISMATCH", "DEVICE_SESSION_CONFLICT")
}

private fun sdirMarkerOracle148(value: String?): Boolean = value != null

private fun sdirMayReallocOracle148(restored: Boolean, chain: List<Pair<Char, String?>>): Boolean =
    when (sdirCodeOracle148(chain)) {
        "DEVICE_IDENTITY_MISMATCH" -> true
        "DEVICE_ID_CONFLICT" -> !restored
        else -> false
    }

private fun sdirMayRealloc1Oracle148(restored: Boolean): Boolean = !restored

private fun sdirShouldReallocOracle148(chain: List<Pair<Char, String?>>, restored: Boolean, attempt: Int, maxAttempts: Int): Boolean =
    sdirMayReallocOracle148(restored, chain) &&
        sdirCodeOracle148(chain) == "DEVICE_IDENTITY_MISMATCH" &&
        attempt >= 0 &&
        maxAttempts > 0 &&
        attempt < maxAttempts - 1

private fun sdirShouldRetryMigOracle148(chain: List<Pair<Char, String?>>, attempt: Int, maxAttempts: Int): Boolean =
    attempt >= 0 &&
        maxAttempts > 0 &&
        attempt < maxAttempts - 1 &&
        sdirCodeOracle148(chain) in setOf("DEVICE_ID_CONFLICT", "DEVICE_IDENTITY_MISMATCH")

// ---------- generators (mirrored by Oracle148 GEN, seed 148002) ----------

private val SDIR_CODE_POOL148 = arrayOf<String?>(
    null, "DEVICE_ID_CONFLICT", "DEVICE_IDENTITY_MISMATCH", "DEVICE_SESSION_CONFLICT",
    "OTHER", "", " device_id_conflict ", "DEVICE_ID_CONFLICT ", "device_id_conflict"
)

private val SDIR_INT_POOL148 = intArrayOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, 2, 3, 4, 5, 254, 255, 256,
    Int.MAX_VALUE - 1, Int.MAX_VALUE
)

private fun genSdirCode148(rnd: Random): String? =
    if (rnd.nextInt(2) == 0) SDIR_CODE_POOL148[rnd.nextInt(SDIR_CODE_POOL148.size)]
    else sdirRandomText148(rnd, 8)

private fun genSdirChain148(rnd: Random): List<Pair<Char, String?>> {
    val n = rnd.nextInt(0, 7)
    return List(n) {
        val kind = "SAIO"[rnd.nextInt(4)]
        kind to genSdirCode148(rnd)
    }
}

private fun genSdirInt148(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) SDIR_INT_POOL148[rnd.nextInt(SDIR_INT_POOL148.size)]
    else rnd.nextInt()

class SignalDeviceIdRecoveryPolicyResidualFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // invalidatesLocalCrypto: an IdentityRecoveryFailedException head
        // invalidates even with no code; otherwise only the two codes do.
        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('I' to null)))
        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('I' to null, 'S' to "OTHER")))
        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('S' to "DEVICE_IDENTITY_MISMATCH")))
        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('A' to "DEVICE_SESSION_CONFLICT")))
        assertTrue(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('O' to null, 'S' to "DEVICE_IDENTITY_MISMATCH")))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('S' to "DEVICE_ID_CONFLICT")))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('S' to "OTHER")))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('O' to null)))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(null))
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('S' to null)))
        assertEquals(
            sdirInvalidatesOracle148(listOf('S' to "DEVICE_IDENTITY_MISMATCH")),
            SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('S' to "DEVICE_IDENTITY_MISMATCH")),
        )
        // An I frame below the first S/A frame does not invalidate: the
        // code walk stops at the first code frame.
        assertFalse(SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148('S' to "OTHER", 'I' to null)))
        // isMigrationPendingMarkerPresent: present means non-null, even blank.
        assertFalse(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(null))
        assertTrue(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(""))
        assertTrue(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(" "))
        assertTrue(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent("pending"))
        assertTrue(SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent("garbage"))
        // mayReallocateDeviceId: mismatch always moves; conflict keeps a
        // restored identity pinned; anything else never moves.
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(true, chainOf148('S' to "DEVICE_IDENTITY_MISMATCH")))
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, chainOf148('S' to "DEVICE_IDENTITY_MISMATCH")))
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(true, chainOf148('S' to "DEVICE_ID_CONFLICT")))
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, chainOf148('S' to "DEVICE_ID_CONFLICT")))
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, chainOf148('S' to "OTHER")))
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, chainOf148('S' to null)))
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, null))
        assertEquals(
            sdirMayReallocOracle148(false, listOf('S' to "DEVICE_ID_CONFLICT")),
            SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false, chainOf148('S' to "DEVICE_ID_CONFLICT")),
        )
        // One-arg overload: only the restored flag matters.
        assertFalse(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(true))
        assertTrue(SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(false))
        // shouldReallocateForIdentityMismatch: mismatch code + bounds.
        val mismatch = chainOf148('A' to "DEVICE_IDENTITY_MISMATCH")
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(mismatch, true, 0, 5))
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(mismatch, false, 3, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(mismatch, true, 4, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(mismatch, true, -1, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(mismatch, true, 0, 1))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(mismatch, true, 0, 0))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(chainOf148('S' to "DEVICE_ID_CONFLICT"), true, 0, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(chainOf148('S' to "OTHER"), true, 0, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(null, true, 0, 5))
        // shouldRetryMigrationCandidate: conflict or mismatch code + bounds.
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "DEVICE_ID_CONFLICT"), 0, 5))
        assertTrue(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "DEVICE_IDENTITY_MISMATCH"), 3, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "DEVICE_SESSION_CONFLICT"), 0, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "OTHER"), 0, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "DEVICE_ID_CONFLICT"), 4, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "DEVICE_ID_CONFLICT"), -1, 5))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148('S' to "DEVICE_ID_CONFLICT"), 0, 1))
        assertFalse(SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(null, 0, 5))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(148002)
        repeat(1500) {
            when (rnd.nextInt(6)) {
                0 -> {
                    val chain = genSdirChain148(rnd)
                    assertEquals(
                        sdirInvalidatesOracle148(chain),
                        SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148(*chain.toTypedArray())),
                    )
                }
                1 -> {
                    val value = if (rnd.nextInt(6) == 0) null else sdirRandomText148(rnd, 8)
                    assertEquals(
                        sdirMarkerOracle148(value),
                        SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(value),
                    )
                }
                2 -> {
                    val chain = genSdirChain148(rnd)
                    val restored = rnd.nextBoolean()
                    assertEquals(
                        sdirMayReallocOracle148(restored, chain),
                        SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(restored, chainOf148(*chain.toTypedArray())),
                    )
                }
                3 -> {
                    val restored = rnd.nextBoolean()
                    assertEquals(
                        sdirMayRealloc1Oracle148(restored),
                        SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(restored),
                    )
                }
                4 -> {
                    val chain = genSdirChain148(rnd)
                    val restored = rnd.nextBoolean()
                    val attempt = genSdirInt148(rnd)
                    val max = genSdirInt148(rnd)
                    assertEquals(
                        sdirShouldReallocOracle148(chain, restored, attempt, max),
                        SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(chainOf148(*chain.toTypedArray()), restored, attempt, max),
                    )
                }
                else -> {
                    val chain = genSdirChain148(rnd)
                    val attempt = genSdirInt148(rnd)
                    val max = genSdirInt148(rnd)
                    assertEquals(
                        sdirShouldRetryMigOracle148(chain, attempt, max),
                        SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148(*chain.toTypedArray()), attempt, max),
                    )
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // All chains of length 0..3 over kinds x codes through the
        // chain-consuming functions.
        val chainCodes = arrayOf<String?>(null, "DEVICE_ID_CONFLICT", "DEVICE_IDENTITY_MISMATCH", "DEVICE_SESSION_CONFLICT", "OTHER")
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
                sdirInvalidatesOracle148(chain),
                SignalDeviceIdRecoveryPolicy.invalidatesLocalCrypto(chainOf148(*chain.toTypedArray())),
            )
            for (restored in listOf(false, true)) {
                assertEquals(
                    sdirMayReallocOracle148(restored, chain),
                    SignalDeviceIdRecoveryPolicy.mayReallocateDeviceId(restored, chainOf148(*chain.toTypedArray())),
                )
            }
        }
        // Marker pool (plus null).
        for (value in listOf<String?>(null, "", " ", "pending", "PENDING", "garbage", "x")) {
            assertEquals(
                sdirMarkerOracle148(value),
                SignalDeviceIdRecoveryPolicy.isMigrationPendingMarkerPresent(value),
            )
        }
        // Attempt/max grid over the short chains.
        val shortChains = chains.filter { it.size <= 2 }
        for (chain in shortChains) {
            for (attempt in arrayOf(-1, 0, 1, 2, 4, Int.MAX_VALUE, Int.MIN_VALUE)) {
                for (mx in arrayOf(-1, 0, 1, 2, 5, Int.MAX_VALUE, Int.MIN_VALUE)) {
                    for (restored in listOf(false, true)) {
                        assertEquals(
                            sdirShouldReallocOracle148(chain, restored, attempt, mx),
                            SignalDeviceIdRecoveryPolicy.shouldReallocateForIdentityMismatch(chainOf148(*chain.toTypedArray()), restored, attempt, mx),
                        )
                    }
                    assertEquals(
                        sdirShouldRetryMigOracle148(chain, attempt, mx),
                        SignalDeviceIdRecoveryPolicy.shouldRetryMigrationCandidate(chainOf148(*chain.toTypedArray()), attempt, mx),
                    )
                }
            }
        }
    }
}
