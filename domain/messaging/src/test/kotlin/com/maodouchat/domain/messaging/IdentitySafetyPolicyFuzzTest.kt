// Fuzz / random-oracle tests for IdentitySafetyPolicy.isStickyWarning
// (checklist line 931, 138th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: isStickyWarning
// just delegates to SafetyCodePolicy.isStickyIdentityWarning, which is
// true only for CHANGED; UNKNOWN/TRUSTED/VERIFIED get the weak banner.
// Only CHANGED must stick to re-verification.
// No fuzz test covered isStickyWarning before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files; the IdentitySafetyPolicy hits in fuzz-96/97 cover warningKind,
// aggregateTrust and canVerifyAny, not this function).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.IdentityTrustState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- oracle ----------

private fun isStickyWarningOracle(trust: IdentityTrustState): Boolean =
    trust == IdentityTrustState.CHANGED

// ---------- generator (mirrored by Oracle138 GEN, seed 138001) ----------

private fun genTrust(rnd: Random): IdentityTrustState =
    IdentityTrustState.entries[rnd.nextInt(IdentityTrustState.entries.size)]

class IdentitySafetyPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Only CHANGED sticks; the other three states never do.
        assertTrue(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.CHANGED))
        assertFalse(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.UNKNOWN))
        assertFalse(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.TRUSTED))
        assertFalse(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.VERIFIED))
        for (trust in IdentityTrustState.entries) {
            assertEquals(isStickyWarningOracle(trust), IdentitySafetyPolicy.isStickyWarning(trust))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle138.genIsw consumption order exactly, so seed
        // 138001 replays the same 1500 GEN lines drive138.py ran.
        val rnd = Random(138001)
        repeat(1500) {
            val trust = genTrust(rnd)
            assertEquals(isStickyWarningOracle(trust), IdentitySafetyPolicy.isStickyWarning(trust))
        }
    }

    @Test
    fun sweepExhaustive() {
        // The whole input space is four enum values; sweep them all.
        for (trust in IdentityTrustState.entries) {
            assertEquals(isStickyWarningOracle(trust), IdentitySafetyPolicy.isStickyWarning(trust))
        }
    }
}
