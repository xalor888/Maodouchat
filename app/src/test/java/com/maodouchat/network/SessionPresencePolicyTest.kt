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
    fun expiredAccessWithExpiredLocalRefreshStillLoggedInUntilServerRevokes() {
        assertTrue(
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

    @Test
    fun expiryExactlyAtNow_countsAsExpired() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now,
                refreshToken = "",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now,
                refreshToken = "r",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun blankUserIdStringIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = " ",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun negativeAccessExpiryTreatedAsUnrecorded() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = -1L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun whitespaceRefreshWithExpiredAccessIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "  ",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiryOneMsInFutureIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now + 1L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun emptyTokenIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun tabNewlineTokenIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "\t\n",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithNullRefreshIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun blankUserIdBeatsPresentRefresh() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = " ",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun paddedTokenAndUserIdStillLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = " access ",
                userId = " u1 ",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun nullTokenIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = null,
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun nullUserIdBeatsLiveRefresh() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = null,
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun freshAccessIgnoresExpiredRefreshRecord() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now - 1L,
                nowMs = now,
            )
        )
    }

    @Test
    fun maxLongAccessExpiryIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = Long.MAX_VALUE,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithBlankTokenIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithRefreshExpiringExactlyNowIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = now,
                nowMs = now,
            )
        )
    }

    @Test
    fun tabPaddedTokenStillLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "\taccess\n",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun minLongAccessExpiryTreatedAsUnrecorded() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = Long.MIN_VALUE,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiredAccessWithPaddedRefreshIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = " r ",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun tabNewlineUserIdIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "\t\n",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun expiryEqualToMaxNowCountsAsExpired() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = Long.MAX_VALUE,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun negativeNowWithPositiveExpiryIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = 1L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = -1_000L,
            )
        )
    }

    @Test
    fun tabPaddedUserIdStillLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "\tu1\t",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun tabOnlyRefreshWithExpiredAccessIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "\t",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    // Kotlin 的 isBlank 把不换行空格也算空白，纯 NBSP token 按空白计、不放行。
    @Test
    fun nonBreakingSpaceTokenCountsAsBlank() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = " ",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun bothTokenAndUserIdMissingIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = null,
                userId = null,
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun bothTokenAndUserIdBlankIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = " ",
                userId = " ",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = "r",
                refreshTokenExpiresAt = now + 86_400_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun unrecordedAccessExpiryWithBlankRefreshIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = 0L,
                refreshToken = "",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun negativeAccessExpiryWithBlankRefreshIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = -5L,
                refreshToken = " ",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun nonBreakingSpaceRefreshWithExpiredAccessIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "\u00A0",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }


    @Test
    fun expiredAccessWithAncientRefreshTimestampStillLoggedIn() {
        // refresh 过期时间戳本身从不参与判定，只看 refresh 是否非空。
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "r",
                refreshTokenExpiresAt = Long.MIN_VALUE,
                nowMs = now,
            )
        )
    }

    @Test
    fun nonBreakingSpaceUserIdIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "\u00A0",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun nonBreakingSpacePaddedTokenStillLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "\u00A0access\u00A0",
                userId = "u1",
                accessTokenExpiresAt = now + 60_000L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun positiveAccessExpiryAfterEpochZeroIsLoggedIn() {
        assertTrue(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = 1L,
                refreshToken = null,
                refreshTokenExpiresAt = 0L,
                nowMs = 0L,
            )
        )
    }

    @Test
    fun emSpaceRefreshWithExpiredAccessIsLoggedOut() {
        assertFalse(
            SessionPresencePolicy.isLoggedIn(
                token = "access",
                userId = "u1",
                accessTokenExpiresAt = now - 1L,
                refreshToken = "\u2003",
                refreshTokenExpiresAt = 0L,
                nowMs = now,
            )
        )
    }
}
