// Fuzz / random-oracle tests for LogoutStorePolicy
// (checklist line 931, 155th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: same-account
// logout and expiry keep the encrypted store (all five wipe
// decisions false); account switch, account deletion and trust-domain
// change wipe it (all five true). All five functions delegate to the
// same destroy decision, so one oracle covers the whole 5 x 5 table.
package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun destroyOracle155(reason: Reason): Boolean =
    when (reason) {
        Reason.LOGOUT, Reason.TOKEN_EXPIRED -> false
        Reason.ACCOUNT_SWITCH, Reason.DELETE_ACCOUNT, Reason.TRUST_DOMAIN_CHANGE -> true
    }

// ---------- generators ----------

internal fun randomReason155(rnd: Random): Reason =
    Reason.entries[rnd.nextInt(Reason.entries.size)]

internal fun logoutWire155(reason: Reason): String = reason.ordinal.toString()

class LogoutStorePolicyFuzzTest {

    private fun check(reason: Reason) {
        val expected = destroyOracle155(reason)
        assertEquals(expected, LogoutStorePolicy.destroyEncryptedDatabase(reason))
        assertEquals(expected, LogoutStorePolicy.wipeOrdinaryMediaCache(reason))
        assertEquals(expected, LogoutStorePolicy.wipeCoilDiskCache(reason))
        assertEquals(expected, LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason))
        assertEquals(expected, LogoutStorePolicy.cancelSenderKeyRetryWork(reason))
    }

    @Test
    fun pinnedBoundaries() {
        // Hand-computed expectations; oracle must agree with both.
        val cases = listOf(
            Reason.LOGOUT to false,
            Reason.TOKEN_EXPIRED to false,
            Reason.ACCOUNT_SWITCH to true,
            Reason.DELETE_ACCOUNT to true,
            Reason.TRUST_DOMAIN_CHANGE to true,
        )
        for ((reason, expected) in cases) {
            assertEquals(expected, destroyOracle155(reason))
            check(reason)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(155001)
        repeat(2000) {
            check(randomReason155(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // The whole 5 x 5 truth table: every reason, every function.
        for (reason in Reason.entries) {
            check(reason)
        }
    }
}
