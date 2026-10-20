package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderKeyRetrySessionPolicyTest {
    @Test
    fun continuesWhenSessionStable() {
        assertTrue(
            SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1")
        )
    }

    @Test
    fun abortsOnLogoutOrSwitch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "", "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u2"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("", "tok", "u1"))
    }

    @Test
    fun blankBatchUserIdAlwaysAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch(" ", "tok", "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\t", "tok", "u1"))
    }

    @Test
    fun blankLiveUserIdAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", " "))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", null))
    }

    @Test
    fun blankLiveTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "  ", "u1"))
    }

    @Test
    fun userMatchIsCaseSensitive() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("U1", "tok", "u1"))
    }

    @Test
    fun stableSessionContinuesRegardlessOfTokenValue() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u9", "any-token-value", "u9"))
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u9", "rotated-token", "u9"))
    }

    @Test
    fun nullTokenAndNullUserIdTogetherAbort() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, null))
    }

    @Test
    fun liveUserIdWithTrailingSpaceDoesNotMatch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1 "))
    }

    @Test
    fun symmetricWhitespacePaddedIdsStillMatch() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch(" u1", "tok", " u1"))
    }

    @Test
    fun tabOnlyTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\t", "u1"))
    }

    @Test
    fun nonAsciiUserIdMatchesExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("用户1", "tok", "用户1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("用户1", "tok", "用户2"))
    }

    @Test
    fun paddedTokenStillCountsAsLive() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", " tok ", "u1"))
    }

    @Test
    fun newlineOnlyTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\n", "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\r\n", "u1"))
    }

    @Test
    fun paddedExpectedIdDoesNotMatchCleanLiveId() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\tu1", "tok", "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "\tu1"))
    }

    @Test
    fun emojiUserIdsMatchExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("用户🚀", "tok", "用户🚀"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("用户🚀", "tok", "用户🛰"))
    }

    @Test
    fun longTokenDoesNotAffectStableSession() {
        val longToken = "x".repeat(10_000)
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", longToken, "u1"))
    }

    @Test
    fun idsWithInternalSpacesMatch() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("user one", "tok", "user one"))
    }

    @Test
    fun repeatedBatchChecksAreDeterministic() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1"))
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1"))
    }

    @Test
    fun userIdPrefixDoesNotMatchSibling() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u12"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u12", "tok", "u1"))
    }

    @Test
    fun newlinePaddedExpectedIdDoesNotMatch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1\n", "tok", "u1"))
    }

    @Test
    fun middleCaseDifferenceDoesNotMatch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("userA1", "tok", "usera1"))
    }

    @Test
    fun batchOwnerIsPerCallNotSticky() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u2"))
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u2", "tok", "u2"))
    }

    @Test
    fun tokenWithInternalNewlineStillCountsAsLive() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok\nx", "u1"))
    }

    @Test
    fun bothIdsBlankAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch(" ", "tok", " "))
    }

    @Test
    fun zeroWidthSpaceIdsMatchExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("\u200Bu1", "tok", "\u200Bu1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\u200Bu1", "tok", "u1"))
    }

    @Test
    fun nonBreakingSpaceOnlyTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\u00A0", "u1"))
    }
}
