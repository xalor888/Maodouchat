// Fuzz / random-oracle tests for NotificationPrivacyPolicy.hideSensitiveDetails
// (checklist line 931, 139th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: details are
// hidden when the app lock is on, or when previews are off -- only the
// (lock on, preview on) combination shows them. Four input combinations
// exist in total, so the sweep is exhaustive by construction.
// No fuzz test covered this function before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files for NotificationPrivacyPolicy / hideSensitiveDetails).
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- oracle ----------

private fun hideSensitiveDetailsOracle(appLockEnabled: Boolean, previewEnabled: Boolean): Boolean =
    appLockEnabled || !previewEnabled

// ---------- generators (mirrored by Oracle139 GEN, seed 139002) ----------

private data class NppCase(
    val appLockEnabled: Boolean,
    val previewEnabled: Boolean,
)

private fun genNppCase(rnd: Random): NppCase =
    NppCase(appLockEnabled = rnd.nextBoolean(), previewEnabled = rnd.nextBoolean())

class NotificationPrivacyPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // App lock on: always hidden, previews or not.
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = false))
        // Lock off and previews off: hidden.
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
        // Only lock off + previews on shows details.
        assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle139.genNpp consumption order exactly, so seed
        // 139002 replays the same 1500 GEN lines drive139.py ran.
        val rnd = Random(139002)
        repeat(1500) {
            val c = genNppCase(rnd)
            assertEquals(
                hideSensitiveDetailsOracle(c.appLockEnabled, c.previewEnabled),
                NotificationPrivacyPolicy.hideSensitiveDetails(c.appLockEnabled, c.previewEnabled)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // All four combinations, exhaustive by construction.
        for (lock in listOf(false, true)) {
            for (preview in listOf(false, true)) {
                assertEquals(
                    hideSensitiveDetailsOracle(lock, preview),
                    NotificationPrivacyPolicy.hideSensitiveDetails(lock, preview)
                )
            }
        }
    }
}
