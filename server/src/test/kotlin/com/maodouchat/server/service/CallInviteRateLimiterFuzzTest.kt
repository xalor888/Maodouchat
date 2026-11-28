package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------- oracle ----------

// Per-char table from OfferProbe191: only ASCII o/O, f/F, e/E, r/R match
// under equals(ignoreCase=true); every probed non-ASCII char matches none.
private fun matchesOfferChar191(c: Char, want: Char): Boolean = when (want) {
    'o' -> c == 'o' || c == 'O'
    'f' -> c == 'f' || c == 'F'
    'e' -> c == 'e' || c == 'E'
    'r' -> c == 'r' || c == 'R'
    else -> false
}

private fun equalsOfferIgnoreCase191(type: String): Boolean {
    if (type.length != 5) return false
    return matchesOfferChar191(type[0], 'o') && matchesOfferChar191(type[1], 'f') &&
        matchesOfferChar191(type[2], 'f') && matchesOfferChar191(type[3], 'e') &&
        matchesOfferChar191(type[4], 'r')
}

private fun isInitialInviteOracle191(type: String, groupId: String, groupInvite: Boolean): Boolean =
    equalsOfferIgnoreCase191(type) && (groupId.isBlank() || groupInvite)

private fun sessionKeyOracle191(callId: String, groupId: String, toUserId: String): String =
    if (groupId.isBlank()) {
        // Blank callId takes the UUID branch in production; the
        // differential never generates one (see the pinned shape check).
        "d:$callId:$toUserId"
    } else {
        "g:$groupId:${callId.ifBlank { "legacy" }}"
    }

private data class Attempt191(val key: String, val timestamp: Long)
private data class DedupEntry191(val hits: Int, val lastAccess: Long)

// Independent re-implementation of the limiter state machine: same
// sweep/dedup/rate-limit rules, plain maps instead of the production
// concurrent structures. Blank callIds are excluded (production mints
// a UUID for them, which no oracle can predict).
private class RateLimiterOracle191(
    private val maxPerMinute: Int,
    private val maxPerTenMinutes: Int,
    private val nowMillis: () -> Long
) {
    private val attemptsByUser = mutableMapOf<String, ArrayDeque<Attempt191>>()
    private val dedupHitsByKey = mutableMapOf<String, DedupEntry191>()
    private var lastSweepAt = 0L

    fun tryAcquire(userId: String, callId: String): CallInviteRateLimitDecision {
        val now = nowMillis()
        if (lastSweepAt == 0L || now < lastSweepAt || now - lastSweepAt > 60_000L) {
            lastSweepAt = now
            val cutoff = now - 600_000L
            attemptsByUser.entries.removeIf { (_, attempts) ->
                attempts.isEmpty() || attempts.all { it.timestamp < cutoff }
            }
            dedupHitsByKey.entries.removeIf { (_, entry) -> entry.lastAccess < cutoff }
        }
        val attempts = attemptsByUser.getOrPut(userId) { ArrayDeque() }
        while (attempts.isNotEmpty() && now - attempts.first().timestamp >= 600_000L) {
            attempts.removeFirst()
        }
        val dedupKey = callId.takeIf { it.isNotBlank() }?.let { "$userId:$it" }
        if (dedupKey != null) {
            val first = attempts.firstOrNull { it.key == callId }
            val hits = dedupHitsByKey[dedupKey]?.hits ?: 0
            val maxDedupHits = if (callId.startsWith("g:")) 500 else 3
            if (hits < maxDedupHits && first != null && now - first.timestamp in 0 until 60_000L) {
                dedupHitsByKey[dedupKey] = DedupEntry191(hits + 1, now)
                return CallInviteRateLimitDecision(allowed = true)
            }
        }
        val lastMinute = attempts.count { now - it.timestamp < 60_000L }
        val blockedBy = when {
            lastMinute >= maxPerMinute ->
                attempts.firstOrNull { now - it.timestamp < 60_000L }?.timestamp?.plus(60_000L)
            attempts.size >= maxPerTenMinutes -> attempts.first().timestamp + 600_000L
            else -> null
        }
        if (blockedBy != null) {
            val ms = (blockedBy - now).coerceAtLeast(1L)
            val retry = ((ms + 999) / 1000).coerceAtLeast(1L)
            return CallInviteRateLimitDecision(allowed = false, retryAfterSeconds = retry)
        }
        attempts.addLast(Attempt191(callId, now))
        return CallInviteRateLimitDecision(allowed = true)
    }
}

private val RATE_USER_POOL191 = listOf("u1", "u2", "alice", "bob", "", "  ", "u-1", "u_2")
private val RATE_CALL_POOL191 = listOf(
    "c1", "c2", "d:abc", "g:grp1", "g:x", "call-1", "x", "d:", "g:",
    "offer", "CALL", "a".repeat(32)
)
private val RATE_GROUP_POOL191 = listOf("", " ", "g1", "grp", "  g  ", "g-2")
private val RATE_TYPE_POOL191 = listOf(
    "offer", "OFFER", "Offer", "oFfEr", "answer", "ANSWER", "ice", "",
    "offe", "offerr", "offer ", " offer", "o\uFF46fer", "\u0130ffer", "o\uFB00er"
)
private const val RATE_TYPE_ALPHABET191 =
    "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ019 _-\u0130\u1E9E\u03A3\u03C3\u212A\u212B\u00C9\u00E9\u00DC\u00FC\u00DF\uFB00\u01C5\uFF2F\uFF4F"

private fun randomType191(r: Random): String {
    if (r.nextInt(3) == 0) return RATE_TYPE_POOL191.random(r)
    val n = r.nextInt(0, 9)
    return buildString { repeat(n) { append(RATE_TYPE_ALPHABET191.random(r)) } }
}

private fun randomCallId191(r: Random): String {
    if (r.nextInt(3) == 0) return RATE_CALL_POOL191.random(r)
    val n = r.nextInt(1, 12)
    return buildString { repeat(n) { append("abcxyz019:d-g_".random(r)) } }
}

private fun randomUser191(r: Random): String {
    if (r.nextInt(3) == 0) return RATE_USER_POOL191.random(r)
    val n = r.nextInt(1, 8)
    return buildString { repeat(n) { append("abcxyz019_-".random(r)) } }
}

// One limiter step: advance the shared fake clock, then tryAcquire.
private data class RateStep191(val deltaMs: Long, val userId: String, val callId: String)

private fun randomRateSequence191(r: Random): List<RateStep191> {
    val n = r.nextInt(1, 31)
    return List(n) {
        val delta = when (r.nextInt(5)) {
            0 -> r.nextLong(0, 1_000)
            1 -> r.nextLong(1_000, 60_000)
            2 -> r.nextLong(60_000, 600_000)
            3 -> r.nextLong(600_000, 3_600_000)
            else -> -r.nextLong(0, 120_000)
        }
        RateStep191(delta, randomUser191(r), randomCallId191(r))
    }
}

// Runs a sequence against the oracle limiter only (used by the
// differential driver); the test file runs prod and oracle side by side.
private fun runRateSequenceOracle191(
    steps: List<RateStep191>,
    maxPerMinute: Int,
    maxPerTenMinutes: Int
): List<CallInviteRateLimitDecision> {
    var now = 1_000_000L
    val oracle = RateLimiterOracle191(maxPerMinute, maxPerTenMinutes) { now }
    val out = ArrayList<CallInviteRateLimitDecision>()
    for (step in steps) {
        now += step.deltaMs
        out.add(oracle.tryAcquire(step.userId, step.callId))
    }
    return out
}

// ---------- tests ----------

class CallInviteRateLimiterFuzzTest {

    @Test
    fun callInviteRateLimiterDifferential191() {
        val r = Random(191003)
        repeat(250) {
            val maxPerMinute = listOf(1, 2, 3, 5).random(r)
            val maxPerTenMinutes = listOf(2, 3, 5, 20).random(r)
            val steps = randomRateSequence191(r)
            val (prodOut, oracleOut) = runRateSequence191(steps, maxPerMinute, maxPerTenMinutes)
            assertEquals(oracleOut, prodOut)
        }
    }

    @Test
    fun callInviteRateLimiterHelpersDifferential191() {
        val r = Random(191004)
        repeat(300) {
            val type = randomType191(r)
            val groupId = RATE_GROUP_POOL191.random(r)
            val groupInvite = r.nextBoolean()
            assertEquals(
                isInitialInviteOracle191(type, groupId, groupInvite),
                CallInviteRateLimiter.isInitialInvite(type, groupId, groupInvite)
            )
            val callId = randomCallId191(r)
            val toUserId = randomUser191(r)
            assertEquals(
                sessionKeyOracle191(callId, groupId, toUserId),
                CallInviteRateLimiter.sessionKey(callId, groupId, toUserId)
            )
        }
    }

    @Test
    fun callInviteRateLimiterPinned191() {
        assertTrue(CallInviteRateLimiter.isInitialInvite("offer", "", false))
        assertTrue(CallInviteRateLimiter.isInitialInvite("OFFER", "", false))
        assertTrue(CallInviteRateLimiter.isInitialInvite("Offer", "g1", true))
        assertTrue(!CallInviteRateLimiter.isInitialInvite("OFFER", "g1", false))
        assertTrue(!CallInviteRateLimiter.isInitialInvite("answer", "", false))
        assertTrue(!CallInviteRateLimiter.isInitialInvite("offer", "g1", false))
        assertTrue(!CallInviteRateLimiter.isInitialInvite("offe", "", false))
        assertTrue(!CallInviteRateLimiter.isInitialInvite("offer ", "", false))
        assertTrue(!CallInviteRateLimiter.isInitialInvite("o\uFF46fer", "", false))
        assertEquals("d:c1:u2", CallInviteRateLimiter.sessionKey("c1", "", "u2"))
        assertEquals("d:c1:u2", CallInviteRateLimiter.sessionKey("c1", "   ", "u2"))
        assertEquals("g:grp:c1", CallInviteRateLimiter.sessionKey("c1", "grp", "u2"))
        assertEquals("g:grp:legacy", CallInviteRateLimiter.sessionKey("", "grp", "u2"))
        assertEquals("g:grp:legacy", CallInviteRateLimiter.sessionKey("   ", "grp", "u2"))
        // Blank 1:1 callId mints a UUID: shape only, never the value.
        val blankKey = CallInviteRateLimiter.sessionKey("", "", "u2")
        assertTrue(blankKey.startsWith("d:legacy_") && blankKey.endsWith(":u2"))
        // 5/min default: five quick invites pass, the sixth is refused.
        var now = 0L
        val limiter = CallInviteRateLimiter(nowMillis = { now })
        repeat(5) {
            assertEquals(
                CallInviteRateLimitDecision(allowed = true),
                limiter.tryAcquire("u1", "c$it")
            )
        }
        val sixth = limiter.tryAcquire("u1", "c5")
        assertEquals(false, sixth.allowed)
        assertEquals(60L, sixth.retryAfterSeconds)
        // Same callId inside the fan-out window is a free dedup hit.
        var now2 = 0L
        val limiter2 = CallInviteRateLimiter(nowMillis = { now2 })
        assertEquals(
            CallInviteRateLimitDecision(allowed = true),
            limiter2.tryAcquire("u1", "g:grp")
        )
        assertEquals(
            CallInviteRateLimitDecision(allowed = true),
            limiter2.tryAcquire("u1", "g:grp")
        )
        assertEquals(
            CallInviteRateLimitDecision(allowed = true),
            limiter2.tryAcquire("u1", "g:grp")
        )
        // Past the 60s fan-out window the same callId counts again.
        now2 += 61_000L
        assertEquals(
            CallInviteRateLimitDecision(allowed = true),
            limiter2.tryAcquire("u1", "g:grp")
        )
        // A direct callId only gets 3 dedup hits; the 4th counts as new.
        var now3 = 0L
        val limiter3 = CallInviteRateLimiter(1, 20, nowMillis = { now3 })
        repeat(4) {
            assertEquals(
                CallInviteRateLimitDecision(allowed = true),
                limiter3.tryAcquire("u1", "d:abc")
            )
        }
        val fifth = limiter3.tryAcquire("u1", "d:abc")
        assertEquals(false, fifth.allowed)
    }
}

private fun runRateSequence191(
    steps: List<RateStep191>,
    maxPerMinute: Int,
    maxPerTenMinutes: Int
): Pair<List<CallInviteRateLimitDecision>, List<CallInviteRateLimitDecision>> {
    var now = 1_000_000L
    val prod = CallInviteRateLimiter(maxPerMinute, maxPerTenMinutes) { now }
    val oracle = RateLimiterOracle191(maxPerMinute, maxPerTenMinutes) { now }
    val prodOut = ArrayList<CallInviteRateLimitDecision>()
    val oracleOut = ArrayList<CallInviteRateLimitDecision>()
    for (step in steps) {
        now += step.deltaMs
        prodOut.add(prod.tryAcquire(step.userId, step.callId))
        oracleOut.add(oracle.tryAcquire(step.userId, step.callId))
    }
    return prodOut to oracleOut
}
