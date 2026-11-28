package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// RateLimiter restated by hand: per-key failure counts plus a locked-until
// map. A key locks once its failure count reaches maxFailures; locking
// clears the count, and the lock lasts lockoutMs from the failing call.
// All clock reads come from the scripted now, never the wall clock.
internal class RateLimiterOracle179(
    private val maxFailures: Int,
    private val lockoutMs: Long,
) {
    private val failureCounts = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()
    var now: Long = 0L

    fun isLocked(key: String): Boolean = remainingLockoutMs(key) > 0L

    fun remainingLockoutMs(key: String): Long {
        val until = lockedUntil[key] ?: return 0L
        val remaining = until - now
        return if (remaining > 0L) remaining else 0L
    }

    fun recordFailure(key: String): Long {
        val failures = (failureCounts[key] ?: 0) + 1
        return if (failures >= maxFailures) {
            lockedUntil[key] = now + lockoutMs
            failureCounts.remove(key)
            lockoutMs
        } else {
            failureCounts[key] = failures
            0L
        }
    }

    fun recordSuccess(key: String) {
        failureCounts.remove(key)
        lockedUntil.remove(key)
    }

    fun reset(key: String) {
        failureCounts.remove(key)
        lockedUntil.remove(key)
    }

    fun resetAll() {
        failureCounts.clear()
        lockedUntil.clear()
    }
}

// AuthenticatedSessionCache restated by hand: a target key maps to the
// user it authenticated plus an expiry. Blank user ids never store, a
// mismatched user never matches, and an expired entry is dropped on read.
internal class AuthenticatedSessionCacheOracle179(
    private val ttlMs: Long,
) {
    private data class Entry(val userId: String, val expiryMillis: Long)

    private val sessions = HashMap<String, Entry>()
    var now: Long = 0L

    fun markAuthenticated(targetKey: String, userId: String) {
        if (userId.isBlank()) return
        sessions[targetKey] = Entry(userId, now + ttlMs)
    }

    fun isAuthenticated(targetKey: String, userId: String): Boolean {
        if (userId.isBlank()) return false
        val entry = sessions[targetKey] ?: return false
        if (entry.userId != userId) return false
        if (now >= entry.expiryMillis) {
            sessions.remove(targetKey)
            return false
        }
        return true
    }

    fun invalidate(targetKey: String) {
        sessions.remove(targetKey)
    }

    fun invalidateUser(userId: String) {
        sessions.entries.removeIf { it.value.userId == userId }
    }

    fun invalidateAll() {
        sessions.clear()
    }
}

// One scripted step: set the clock, then run one limiter/cache op.
// k: rf recordFailure, il isLocked, rl remainingLockoutMs,
// rs recordSuccess, r reset, ra resetAll,
// ma markAuthenticated, ia isAuthenticated,
// inv invalidate, iu invalidateUser, iaa invalidateAll.
internal data class RateScriptOp179(
    val t: Long,
    val k: String,
    val key: String,
    val user: String?,
)

// Run a whole script through the hand-rolled oracles, collecting one
// JSON value per op (number, true/false, or null for Unit ops).
internal fun runRateScriptOracle179(
    maxFailures: Int,
    lockoutMs: Long,
    ttlMs: Long,
    ops: List<RateScriptOp179>,
): String {
    val limiter = RateLimiterOracle179(maxFailures, lockoutMs)
    val cache = AuthenticatedSessionCacheOracle179(ttlMs)
    val out = ArrayList<String>()
    for (op in ops) {
        limiter.now = op.t
        cache.now = op.t
        when (op.k) {
            "rf" -> out.add(limiter.recordFailure(op.key).toString())
            "il" -> out.add(limiter.isLocked(op.key).toString())
            "rl" -> out.add(limiter.remainingLockoutMs(op.key).toString())
            "rs" -> { limiter.recordSuccess(op.key); out.add("null") }
            "r" -> { limiter.reset(op.key); out.add("null") }
            "ra" -> { limiter.resetAll(); out.add("null") }
            "ma" -> { cache.markAuthenticated(op.key, op.user ?: ""); out.add("null") }
            "ia" -> out.add(cache.isAuthenticated(op.key, op.user ?: "").toString())
            "inv" -> { cache.invalidate(op.key); out.add("null") }
            "iu" -> { cache.invalidateUser(op.user ?: ""); out.add("null") }
            "iaa" -> { cache.invalidateAll(); out.add("null") }
            else -> error("unknown op ${op.k}")
        }
    }
    return "[" + out.joinToString(",") + "]"
}

// Production runRateScript runs the same script against the real
// RateLimiter / AuthenticatedSessionCache behind a scripted clock.
internal fun runRateScriptProduction179(
    maxFailures: Int,
    lockoutMs: Long,
    ttlMs: Long,
    ops: List<RateScriptOp179>,
): String {
    var now = 0L
    val limiter = RateLimiter(maxFailures, lockoutMs) { now }
    val cache = AuthenticatedSessionCache(ttlMs) { now }
    val out = ArrayList<String>()
    for (op in ops) {
        now = op.t
        when (op.k) {
            "rf" -> out.add(limiter.recordFailure(op.key).toString())
            "il" -> out.add(limiter.isLocked(op.key).toString())
            "rl" -> out.add(limiter.remainingLockoutMs(op.key).toString())
            "rs" -> { limiter.recordSuccess(op.key); out.add("null") }
            "r" -> { limiter.reset(op.key); out.add("null") }
            "ra" -> { limiter.resetAll(); out.add("null") }
            "ma" -> { cache.markAuthenticated(op.key, op.user ?: ""); out.add("null") }
            "ia" -> out.add(cache.isAuthenticated(op.key, op.user ?: "").toString())
            "inv" -> { cache.invalidate(op.key); out.add("null") }
            "iu" -> { cache.invalidateUser(op.user ?: ""); out.add("null") }
            "iaa" -> { cache.invalidateAll(); out.add("null") }
            else -> error("unknown op ${op.k}")
        }
    }
    return "[" + out.joinToString(",") + "]"
}

// ---------- generators ----------

internal data class RateScriptCase179(
    val maxFailures: Int,
    val lockoutMs: Long,
    val ttlMs: Long,
    val ops: List<RateScriptOp179>,
)

internal val RATE_MAX_FAILURES_POOL179: List<Int> = listOf(
    -1, 0, 1, 2, 3, 5, 8, 100, Int.MAX_VALUE,
)

internal val RATE_LOCKOUT_POOL179: List<Long> = listOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -30_000L, -1L, 0L, 1L, 1_000L,
    30_000L, 300_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal val RATE_TTL_POOL179: List<Long> = listOf(
    Long.MIN_VALUE, -300_000L, -1L, 0L, 1L, 60_000L, 300_000L,
    Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal val RATE_CLOCK_POOL179: List<Long> = listOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -300_001L, -300_000L, -30_001L,
    -30_000L, -1L, 0L, 1L, 29_999L, 30_000L, 30_001L, 60_000L,
    299_999L, 300_000L, 300_001L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal val RATE_KEY_POOL179: List<String> = listOf(
    "", " ", "key1", "alice", "bob", "  alice  ", "alice\nbob",
    "a".repeat(100), "u\u00e9", "k\u00a0", "\u00a0k\u00a0",
    "a\uD83D\uDE00b", "key\twith\ttabs", "KEY1",
)

internal val RATE_OP_KINDS179: List<String> = listOf(
    "rf", "il", "rl", "rs", "r", "ra", "ma", "ia", "inv", "iu", "iaa",
)

internal fun randomRateScriptCase179(rnd: Random): RateScriptCase179 {
    fun randomKey(): String {
        if (rnd.nextInt(2) == 0) {
            return RATE_KEY_POOL179[rnd.nextInt(RATE_KEY_POOL179.size)]
        }
        val alphabet = "abk _-\t\n\u00a0\u00e9"
        val len = rnd.nextInt(0, 12)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    fun randomClock(): Long {
        if (rnd.nextInt(2) == 0) {
            return RATE_CLOCK_POOL179[rnd.nextInt(RATE_CLOCK_POOL179.size)]
        }
        return rnd.nextLong(-600_000L, 600_001L)
    }
    val n = rnd.nextInt(1, 13)
    val ops = ArrayList<RateScriptOp179>()
    repeat(n) {
        val k = RATE_OP_KINDS179[rnd.nextInt(RATE_OP_KINDS179.size)]
        ops.add(
            RateScriptOp179(
                t = randomClock(),
                k = k,
                key = randomKey(),
                user = if (k == "ma" || k == "ia" || k == "iu") randomKey() else null,
            )
        )
    }
    return RateScriptCase179(
        maxFailures = if (rnd.nextInt(2) == 0) {
            RATE_MAX_FAILURES_POOL179[rnd.nextInt(RATE_MAX_FAILURES_POOL179.size)]
        } else {
            rnd.nextInt(-2, 10)
        },
        lockoutMs = if (rnd.nextInt(2) == 0) {
            RATE_LOCKOUT_POOL179[rnd.nextInt(RATE_LOCKOUT_POOL179.size)]
        } else {
            rnd.nextLong(-60_000L, 600_001L)
        },
        ttlMs = if (rnd.nextInt(2) == 0) {
            RATE_TTL_POOL179[rnd.nextInt(RATE_TTL_POOL179.size)]
        } else {
            rnd.nextLong(-60_000L, 600_001L)
        },
        ops = ops,
    )
}

internal fun escJsonRate179(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun escJsonOrNullRate179(s: String?): String =
    if (s == null) "null" else escJsonRate179(s)

internal fun rateScriptWireJson179(c: RateScriptCase179): String {
    val ops = c.ops.joinToString(",") { op ->
        "{\"t\":${op.t}," +
            "\"k\":${escJsonRate179(op.k)}," +
            "\"key\":${escJsonRate179(op.key)}," +
            "\"user\":${escJsonOrNullRate179(op.user)}}"
    }
    return "{\"maxFailures\":${c.maxFailures}," +
        "\"lockoutMs\":${c.lockoutMs}," +
        "\"ttlMs\":${c.ttlMs}," +
        "\"ops\":[$ops]}"
}

class PinSecurityPolicyModelsFuzzTest {

    private fun checkRateScript179(c: RateScriptCase179) {
        assertEquals(
            runRateScriptOracle179(c.maxFailures, c.lockoutMs, c.ttlMs, c.ops),
            runRateScriptProduction179(c.maxFailures, c.lockoutMs, c.ttlMs, c.ops),
            "rateScript($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every failure cap against every lockout/ttl edge, one short
        // script per combination: five failures then a lock probe.
        for (maxFailures in RATE_MAX_FAILURES_POOL179) {
            for (lockoutMs in RATE_LOCKOUT_POOL179) {
                for (ttlMs in RATE_TTL_POOL179) {
                    val ops = ArrayList<RateScriptOp179>()
                    repeat(6) { i ->
                        ops.add(RateScriptOp179(t = i * 1000L, k = "rf", key = "alice", user = null))
                        ops.add(RateScriptOp179(t = i * 1000L, k = "il", key = "alice", user = null))
                        ops.add(RateScriptOp179(t = i * 1000L, k = "rl", key = "alice", user = null))
                    }
                    ops.add(RateScriptOp179(t = 30_000L, k = "ma", key = "sess", user = "alice"))
                    ops.add(RateScriptOp179(t = 30_001L, k = "ia", key = "sess", user = "alice"))
                    ops.add(RateScriptOp179(t = 330_001L, k = "ia", key = "sess", user = "alice"))
                    checkRateScript179(
                        RateScriptCase179(maxFailures, lockoutMs, ttlMs, ops)
                    )
                }
            }
        }
        // Every key shape through a lock/unlock cycle.
        for (key in RATE_KEY_POOL179) {
            val ops = listOf(
                RateScriptOp179(t = 0L, k = "rf", key = key, user = null),
                RateScriptOp179(t = 0L, k = "rf", key = key, user = null),
                RateScriptOp179(t = 0L, k = "rf", key = key, user = null),
                RateScriptOp179(t = 0L, k = "rf", key = key, user = null),
                RateScriptOp179(t = 0L, k = "rf", key = key, user = null),
                RateScriptOp179(t = 0L, k = "il", key = key, user = null),
                RateScriptOp179(t = 30_000L, k = "il", key = key, user = null),
                RateScriptOp179(t = 30_001L, k = "il", key = key, user = null),
                RateScriptOp179(t = 0L, k = "rs", key = key, user = null),
                RateScriptOp179(t = 0L, k = "il", key = key, user = null),
            )
            checkRateScript179(RateScriptCase179(5, 30_000L, 300_000L, ops))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(179001)
        repeat(4000) { checkRateScript179(randomRateScriptCase179(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Failure counts 0..10 at every clock in the lockout window.
        for (failures in 0..10) {
            for (t in listOf(0L, 1L, 29_999L, 30_000L, 30_001L, 60_000L)) {
                val ops = ArrayList<RateScriptOp179>()
                repeat(failures) {
                    ops.add(RateScriptOp179(t = 0L, k = "rf", key = "k", user = null))
                }
                ops.add(RateScriptOp179(t = t, k = "il", key = "k", user = null))
                ops.add(RateScriptOp179(t = t, k = "rl", key = "k", user = null))
                checkRateScript179(RateScriptCase179(5, 30_000L, 300_000L, ops))
            }
        }
        // Clock sweep across the ttl boundary for the session cache.
        for (t in 0L..600_000L step 9973L) {
            val ops = listOf(
                RateScriptOp179(t = 0L, k = "ma", key = "s", user = "u"),
                RateScriptOp179(t = t, k = "ia", key = "s", user = "u"),
                RateScriptOp179(t = t, k = "ia", key = "s", user = "other"),
            )
            checkRateScript179(RateScriptCase179(5, 30_000L, 300_000L, ops))
        }
        // Lockout arithmetic at the Long wrap boundary.
        for (now in listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1)) {
            val ops = listOf(
                RateScriptOp179(t = now, k = "rf", key = "k", user = null),
                RateScriptOp179(t = now, k = "rl", key = "k", user = null),
                RateScriptOp179(t = now, k = "ma", key = "s", user = "u"),
                RateScriptOp179(t = now, k = "ia", key = "s", user = "u"),
            )
            checkRateScript179(RateScriptCase179(1, Long.MAX_VALUE, Long.MAX_VALUE, ops))
        }
    }
}
