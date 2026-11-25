// Fuzz / random-oracle tests for SessionPresencePolicy
// (checklist line 931, 154th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: blank token or
// blank user id is logged out; an access token that is unrecorded
// (<= 0) or still fresh (> nowMs) is logged in; an expired access token
// stays logged in only while a non-blank refresh token exists. The
// refresh expiry is never consulted by production, so the oracle ignores
// it too.
package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun isLoggedInOracle154(
    tok: String?,
    userId: String?,
    accessTokenExpiresAt: Long,
    refreshToken: String?,
    refreshTokenExpiresAt: Long,
    nowMs: Long,
): Boolean {
    if (tok.isNullOrBlank()) return false
    if (userId.isNullOrBlank()) return false
    if (accessTokenExpiresAt <= 0L || accessTokenExpiresAt > nowMs) return true
    return !refreshToken.isNullOrBlank()
}

// ---------- generators ----------

internal data class SessionCase154(
    val token: String?,
    val userId: String?,
    val accessTokenExpiresAt: Long,
    val refreshToken: String?,
    val refreshTokenExpiresAt: Long,
    val nowMs: Long,
)

internal val SESSION_NOW154 = 1_700_000_000_000L

internal val SESSION_TOKEN_POOL154 = listOf<String?>(
    null, "", " ", "tok", "\u00a0", "\u0085", "\u2003", "t",
)

internal val SESSION_USER_POOL154 = listOf<String?>(
    null, "", " ", "u1", "\u00a0", "\u0085",
)

internal fun randomSessionToken154(rnd: Random): String? =
    if (rnd.nextInt(10) < 8) SESSION_TOKEN_POOL154[rnd.nextInt(SESSION_TOKEN_POOL154.size)]
    else "tok" + rnd.nextInt(100)

internal fun randomSessionUser154(rnd: Random): String? =
    if (rnd.nextInt(10) < 8) SESSION_USER_POOL154[rnd.nextInt(SESSION_USER_POOL154.size)]
    else "u" + rnd.nextInt(100)

internal fun randomSessionExpiry154(rnd: Random, now: Long): Long = when (rnd.nextInt(10)) {
    0 -> listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)[rnd.nextInt(5)]
    1 -> now + rnd.nextLong(-100_000L, 100_001L)
    2 -> rnd.nextLong(-1_000_000L, 1_000_001L)
    else -> now + rnd.nextLong(-86_400_000L, 86_400_001L)
}

internal fun randomSessionCase154(rnd: Random): SessionCase154 {
    val now = SESSION_NOW154 + rnd.nextLong(-10_000L, 10_001L)
    val refresh = when (rnd.nextInt(10)) {
        0 -> null
        1 -> listOf("", " ", "\u00a0", "\u0085")[rnd.nextInt(4)]
        else -> "r" + rnd.nextInt(100)
    }
    return SessionCase154(
        token = randomSessionToken154(rnd),
        userId = randomSessionUser154(rnd),
        accessTokenExpiresAt = randomSessionExpiry154(rnd, now),
        refreshToken = refresh,
        refreshTokenExpiresAt = randomSessionExpiry154(rnd, now),
        nowMs = now,
    )
}

internal fun sessionCaseWireJson154(case: SessionCase154): String {
    val sb = StringBuilder()
    sb.append("{")
    if (case.token != null) sb.append("\"t\":\"").append(jsonEscape154(case.token)).append("\"")
    if (case.userId != null) {
        if (sb.length > 1) sb.append(",")
        sb.append("\"u\":\"").append(jsonEscape154(case.userId)).append("\"")
    }
    if (sb.length > 1) sb.append(",")
    sb.append("\"ae\":").append(case.accessTokenExpiresAt)
    if (case.refreshToken != null) sb.append(",\"rt\":\"").append(jsonEscape154(case.refreshToken)).append("\"")
    sb.append(",\"re\":").append(case.refreshTokenExpiresAt)
    sb.append(",\"now\":").append(case.nowMs)
    sb.append("}")
    return sb.toString()
}

class SessionPresencePolicyFuzzTest {

    private fun check(case: SessionCase154) {
        assertEquals(
            isLoggedInOracle154(case.token, case.userId, case.accessTokenExpiresAt, case.refreshToken, case.refreshTokenExpiresAt, case.nowMs),
            SessionPresencePolicy.isLoggedIn(case.token, case.userId, case.accessTokenExpiresAt, case.refreshToken, case.refreshTokenExpiresAt, case.nowMs),
        )
    }

    @Test
    fun pinnedBoundaries() {
        val now = SESSION_NOW154
        val cases = listOf(
            SessionCase154(null, "u1", now + 60_000L, "r", now + 86_400_000L, now) to false,
            SessionCase154("", "u1", now + 60_000L, "r", now + 86_400_000L, now) to false,
            SessionCase154(" ", "u1", now + 60_000L, "r", now + 86_400_000L, now) to false,
            SessionCase154("\u00a0", "u1", now + 60_000L, "r", now + 86_400_000L, now) to false,
            // NEL is not blank: a NEL token passes the blank checks.
            SessionCase154("\u0085", "u1", now + 60_000L, "r", now + 86_400_000L, now) to true,
            SessionCase154("tok", null, now + 60_000L, "r", now + 86_400_000L, now) to false,
            SessionCase154("tok", "", now + 60_000L, "r", now + 86_400_000L, now) to false,
            SessionCase154("tok", "u1", 0L, null, 0L, now) to true,
            SessionCase154("tok", "u1", -5L, null, 0L, now) to true,
            SessionCase154("tok", "u1", Long.MIN_VALUE, null, 0L, now) to true,
            SessionCase154("tok", "u1", Long.MAX_VALUE, null, 0L, now) to true,
            SessionCase154("tok", "u1", now + 60_000L, null, 0L, now) to true,
            // Expired access: exactly at now is expired (not > now).
            SessionCase154("tok", "u1", now, "r", now + 86_400_000L, now) to true,
            SessionCase154("tok", "u1", now, null, now + 86_400_000L, now) to false,
            SessionCase154("tok", "u1", now - 1L, "r", now + 86_400_000L, now) to true,
            SessionCase154("tok", "u1", now - 1L, null, now + 86_400_000L, now) to false,
            SessionCase154("tok", "u1", now - 1L, "", now + 86_400_000L, now) to false,
            SessionCase154("tok", "u1", now - 1L, " ", now + 86_400_000L, now) to false,
            SessionCase154("tok", "u1", now - 1L, "\u0085", now + 86_400_000L, now) to true,
            // Refresh expiry is never consulted by production.
            SessionCase154("tok", "u1", now - 1L, "r", Long.MIN_VALUE, now) to true,
            SessionCase154("tok", "u1", now - 1L, "r", Long.MAX_VALUE, now) to true,
            SessionCase154("tok", "u1", now - 1L, null, Long.MAX_VALUE, now) to false,
            SessionCase154("tok", "u1", now - 1L, null, Long.MIN_VALUE, now) to false,
        )
        for ((case, expected) in cases) {
            assertEquals(expected, isLoggedInOracle154(case.token, case.userId, case.accessTokenExpiresAt, case.refreshToken, case.refreshTokenExpiresAt, case.nowMs))
            assertEquals(
                isLoggedInOracle154(case.token, case.userId, case.accessTokenExpiresAt, case.refreshToken, case.refreshTokenExpiresAt, case.nowMs),
                SessionPresencePolicy.isLoggedIn(case.token, case.userId, case.accessTokenExpiresAt, case.refreshToken, case.refreshTokenExpiresAt, case.nowMs),
            )
            check(case)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(154003)
        repeat(2000) {
            check(randomSessionCase154(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        val now = SESSION_NOW154
        for (token in listOf<String?>(null, "", " ", "t", "\u00a0", "\u0085")) {
            for (user in listOf<String?>(null, "", "u1", " ")) {
                for (accessExp in listOf(Long.MIN_VALUE, -1L, 0L, now - 1L, now, now + 1L, Long.MAX_VALUE)) {
                    for (refresh in listOf<String?>(null, "", "r", " ")) {
                        for (refreshExp in listOf(0L, Long.MAX_VALUE)) {
                            check(SessionCase154(token, user, accessExp, refresh, refreshExp, now))
                        }
                    }
                }
            }
        }
    }
}
