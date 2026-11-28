// Fuzz / random-oracle tests for SignalInitializationPolicy
// (checklist line 931, 129th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the REUSE /
// UPLOAD_ONLY / FULL_INITIALIZATION action decision and the small state
// transitions (selectAccount, localStoreReady, publicationSucceeded,
// publicationFailed, cleared) plus the canReuse / canUseLocalCrypto
// gates are pure comparisons over SignalInitializationState. No
// SignalInitializationPolicy fuzz test existed before this block.
package com.maodouchat.core.crypto

import com.maodouchat.core.model.SignalInitializationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

private fun sipActionOracle(
    state: SignalInitializationState,
    requested: String?
): SignalInitializationAction = when {
    state.accountId == requested && state.localCryptoReady && state.publicationReady ->
        SignalInitializationAction.REUSE
    state.accountId == requested && state.localCryptoReady ->
        SignalInitializationAction.UPLOAD_ONLY
    else -> SignalInitializationAction.FULL_INITIALIZATION
}

private fun sipSelectOracle(
    state: SignalInitializationState,
    requested: String?
): SignalInitializationState =
    if (state.accountId == requested) {
        state.copy(localCryptoReady = false, publicationReady = false)
    } else {
        SignalInitializationState(accountId = requested)
    }

private fun sipLocalReadyOracle(state: SignalInitializationState): SignalInitializationState =
    state.copy(localCryptoReady = true)

private fun sipPubOkOracle(state: SignalInitializationState): SignalInitializationState =
    state.copy(publicationReady = state.localCryptoReady && state.accountId != null)

private fun sipPubFailOracle(
    state: SignalInitializationState,
    invalidate: Boolean
): SignalInitializationState = state.copy(
    localCryptoReady = state.localCryptoReady && !invalidate,
    publicationReady = false
)

private fun sipCanReuseOracle(current: String?, initOk: Boolean, requested: String?): Boolean =
    sipActionOracle(
        SignalInitializationState(
            accountId = current,
            localCryptoReady = true,
            publicationReady = initOk
        ),
        requested
    ) == SignalInitializationAction.REUSE && !requested.isNullOrBlank()

private fun sipCanUseOracle(current: String?, localReady: Boolean, requested: String?): Boolean =
    localReady && !requested.isNullOrBlank() && current == requested

// ---------- generators (mirrored by Oracle129 GEN, seed 129003) ----------

private val SIP_ID_POOL = arrayOf(
    "", " ", "  ", "\u00A0", "\u2003", "u1", "u2", "alice", "bob",
    "a\uD800b", "\uDC00"
)

private fun genSipId(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null
    else if (rnd.nextInt(2) == 0) SIP_ID_POOL[rnd.nextInt(SIP_ID_POOL.size)]
    else randomText(rnd, 12)

private fun genSipState(rnd: Random): SignalInitializationState =
    SignalInitializationState(
        accountId = genSipId(rnd),
        localCryptoReady = rnd.nextBoolean(),
        publicationReady = rnd.nextBoolean()
    )

class SignalInitializationPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val empty = SignalInitializationState()
        val ready = SignalInitializationState(accountId = "u1", localCryptoReady = true, publicationReady = true)
        val localOnly = SignalInitializationState(accountId = "u1", localCryptoReady = true)
        val pubOnly = SignalInitializationState(accountId = "u1", publicationReady = true)
        // action: account must match (null matches null); REUSE needs
        // both flags, UPLOAD_ONLY needs local only.
        assertEquals(SignalInitializationAction.REUSE, SignalInitializationPolicy.action(ready, "u1"))
        assertEquals(SignalInitializationAction.UPLOAD_ONLY, SignalInitializationPolicy.action(localOnly, "u1"))
        assertEquals(SignalInitializationAction.FULL_INITIALIZATION, SignalInitializationPolicy.action(pubOnly, "u1"))
        assertEquals(SignalInitializationAction.FULL_INITIALIZATION, SignalInitializationPolicy.action(ready, "u2"))
        assertEquals(SignalInitializationAction.FULL_INITIALIZATION, SignalInitializationPolicy.action(ready, null))
        assertEquals(SignalInitializationAction.FULL_INITIALIZATION, SignalInitializationPolicy.action(empty, "u1"))
        assertEquals(SignalInitializationAction.REUSE, SignalInitializationPolicy.action(
            SignalInitializationState(accountId = null, localCryptoReady = true, publicationReady = true), null))
        assertEquals(SignalInitializationAction.FULL_INITIALIZATION, SignalInitializationPolicy.action(
            SignalInitializationState(accountId = null, localCryptoReady = true, publicationReady = true), ""))
        // selectAccount: same account resets the flags in place; a
        // different account starts a fresh state for the new id.
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = false, publicationReady = false),
            SignalInitializationPolicy.selectAccount(ready, "u1")
        )
        assertEquals(
            SignalInitializationState(accountId = "u2"),
            SignalInitializationPolicy.selectAccount(ready, "u2")
        )
        assertEquals(
            SignalInitializationState(accountId = null),
            SignalInitializationPolicy.selectAccount(ready, null)
        )
        assertEquals(
            SignalInitializationState(accountId = null, localCryptoReady = false, publicationReady = false),
            SignalInitializationPolicy.selectAccount(empty, null)
        )
        // localStoreReady only flips localCryptoReady.
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = true, publicationReady = true),
            SignalInitializationPolicy.localStoreReady(ready)
        )
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = true, publicationReady = true),
            SignalInitializationPolicy.localStoreReady(pubOnly)
        )
        // publicationSucceeded needs local ready AND a non-null account.
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = true, publicationReady = true),
            SignalInitializationPolicy.publicationSucceeded(localOnly)
        )
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = false, publicationReady = false),
            SignalInitializationPolicy.publicationSucceeded(SignalInitializationState(accountId = "u1"))
        )
        assertEquals(
            SignalInitializationState(accountId = null, localCryptoReady = true, publicationReady = false),
            SignalInitializationPolicy.publicationSucceeded(SignalInitializationState(accountId = null, localCryptoReady = true))
        )
        // publicationFailed always clears publicationReady; local survives
        // unless invalidateLocalCrypto is set.
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = true, publicationReady = false),
            SignalInitializationPolicy.publicationFailed(ready)
        )
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = false, publicationReady = false),
            SignalInitializationPolicy.publicationFailed(ready, invalidateLocalCrypto = true)
        )
        assertEquals(
            SignalInitializationState(accountId = "u1", localCryptoReady = false, publicationReady = false),
            SignalInitializationPolicy.publicationFailed(SignalInitializationState(accountId = "u1"), invalidateLocalCrypto = true)
        )
        assertEquals(empty, SignalInitializationPolicy.cleared())
        // canReuse: needs matching non-blank requested id and a
        // successful initialization; null/blank requested never reuses.
        assertTrue(SignalInitializationPolicy.canReuse("u1", true, "u1"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", false, "u1"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, "u2"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, null))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, ""))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, " "))
        assertFalse(SignalInitializationPolicy.canReuse(null, true, "u1"))
        assertFalse(SignalInitializationPolicy.canReuse(null, true, null))
        // canUseLocalCrypto: local must be ready, requested non-blank,
        // and the current account must equal the requested one.
        assertTrue(SignalInitializationPolicy.canUseLocalCrypto("u1", true, "u1"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", false, "u1"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, "u2"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, null))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto("u1", true, " "))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto(null, true, "u1"))
        assertFalse(SignalInitializationPolicy.canUseLocalCrypto(null, true, null))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle129.genSip consumption order exactly, so seed
        // 129003 replays the same 1500 GEN lines drive129.py ran.
        val rnd = Random(129003)
        repeat(1500) {
            when (rnd.nextInt(8)) {
                0 -> {
                    val state = genSipState(rnd)
                    val req = genSipId(rnd)
                    assertEquals(sipActionOracle(state, req), SignalInitializationPolicy.action(state, req))
                }
                1 -> {
                    val state = genSipState(rnd)
                    val req = genSipId(rnd)
                    assertEquals(sipSelectOracle(state, req), SignalInitializationPolicy.selectAccount(state, req))
                }
                2 -> {
                    val state = genSipState(rnd)
                    assertEquals(sipLocalReadyOracle(state), SignalInitializationPolicy.localStoreReady(state))
                }
                3 -> {
                    val state = genSipState(rnd)
                    assertEquals(sipPubOkOracle(state), SignalInitializationPolicy.publicationSucceeded(state))
                }
                4 -> {
                    val state = genSipState(rnd)
                    val inv = rnd.nextBoolean()
                    assertEquals(sipPubFailOracle(state, inv), SignalInitializationPolicy.publicationFailed(state, inv))
                }
                5 -> {
                    assertEquals(SignalInitializationState(), SignalInitializationPolicy.cleared())
                }
                6 -> {
                    val cur = genSipId(rnd)
                    val ok = rnd.nextBoolean()
                    val req = genSipId(rnd)
                    assertEquals(sipCanReuseOracle(cur, ok, req), SignalInitializationPolicy.canReuse(cur, ok, req))
                }
                else -> {
                    val cur = genSipId(rnd)
                    val ready = rnd.nextBoolean()
                    val req = genSipId(rnd)
                    assertEquals(sipCanUseOracle(cur, ready, req), SignalInitializationPolicy.canUseLocalCrypto(cur, ready, req))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // action / selectAccount over every state x requested id.
        val ids = arrayOf<String?>(null, "", " ", "\u00A0", "u1", "u2", "alice")
        for (acct in ids) {
            for (local in listOf(false, true)) {
                for (pub in listOf(false, true)) {
                    val state = SignalInitializationState(accountId = acct, localCryptoReady = local, publicationReady = pub)
                    for (req in ids) {
                        assertEquals(sipActionOracle(state, req), SignalInitializationPolicy.action(state, req))
                        assertEquals(sipSelectOracle(state, req), SignalInitializationPolicy.selectAccount(state, req))
                    }
                    assertEquals(sipLocalReadyOracle(state), SignalInitializationPolicy.localStoreReady(state))
                    assertEquals(sipPubOkOracle(state), SignalInitializationPolicy.publicationSucceeded(state))
                    for (inv in listOf(false, true)) {
                        assertEquals(sipPubFailOracle(state, inv), SignalInitializationPolicy.publicationFailed(state, inv))
                    }
                }
            }
        }
        // canReuse / canUseLocalCrypto over id x bool x id.
        for (cur in ids) {
            for (ok in listOf(false, true)) {
                for (req in ids) {
                    assertEquals(sipCanReuseOracle(cur, ok, req), SignalInitializationPolicy.canReuse(cur, ok, req))
                    assertEquals(sipCanUseOracle(cur, ok, req), SignalInitializationPolicy.canUseLocalCrypto(cur, ok, req))
                }
            }
        }
        // Whitespace / control singles as ids.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(sipActionOracle(SignalInitializationState(accountId = s, localCryptoReady = true, publicationReady = true), s),
                SignalInitializationPolicy.action(SignalInitializationState(accountId = s, localCryptoReady = true, publicationReady = true), s))
            assertEquals(sipCanReuseOracle(s, true, s), SignalInitializationPolicy.canReuse(s, true, s))
            assertEquals(sipCanUseOracle(s, true, s), SignalInitializationPolicy.canUseLocalCrypto(s, true, s))
        }
        for (u in 0x00..0x1F) {
            val s = u.toChar().toString()
            assertEquals(sipCanReuseOracle(s, true, s), SignalInitializationPolicy.canReuse(s, true, s))
        }
    }
}
