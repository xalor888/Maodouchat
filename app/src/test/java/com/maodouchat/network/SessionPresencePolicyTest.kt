package com.maodouchat.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPresencePolicyTest {
    private val now = 1_700_000_000_000L

    @Test
    fun blankTokenIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = " ",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun missingUserIdIsLoggedOutEvenWithFreshAccess() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = null,
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun validAccessWithoutRefreshIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithoutRefreshIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithExpiredRefreshIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = now - 1L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithLiveRefreshIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun unrecordedAccessExpiryIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = 0L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }
}
