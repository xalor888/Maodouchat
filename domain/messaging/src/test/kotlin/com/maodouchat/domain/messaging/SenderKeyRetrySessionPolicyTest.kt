package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderKeyRetrySessionPolicyTest {
    @Test
    fun continuesWhenSessionStable() {
        assertTrue(
            SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1", false)
        )
    }

    @Test
    fun abortsOnLogoutOrSwitch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "", "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u2", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("", "tok", "u1", false))
    }

    @Test
    fun blankBatchUserIdAlwaysAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch(" ", "tok", "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\t", "tok", "u1", false))
    }

    @Test
    fun blankLiveUserIdAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", " ", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", null, false))
    }

    @Test
    fun blankLiveTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "  ", "u1", false))
    }

    @Test
    fun userMatchIsCaseSensitive() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("U1", "tok", "u1", false))
    }

    @Test
    fun stableSessionContinuesRegardlessOfTokenValue() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u9", "any-token-value", "u9", false))
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u9", "rotated-token", "u9", false))
    }

    @Test
    fun nullTokenAndNullUserIdTogetherAbort() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, null, false))
    }

    @Test
    fun liveUserIdWithTrailingSpaceDoesNotMatch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1 ", false))
    }

    @Test
    fun symmetricWhitespacePaddedIdsStillMatch() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch(" u1", "tok", " u1", false))
    }

    @Test
    fun tabOnlyTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\t", "u1", false))
    }

    @Test
    fun nonAsciiUserIdMatchesExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("用户1", "tok", "用户1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("用户1", "tok", "用户2", false))
    }

    @Test
    fun paddedTokenStillCountsAsLive() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", " tok ", "u1", false))
    }

    @Test
    fun newlineOnlyTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\n", "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\r\n", "u1", false))
    }

    @Test
    fun paddedExpectedIdDoesNotMatchCleanLiveId() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\tu1", "tok", "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "\tu1", false))
    }

    @Test
    fun emojiUserIdsMatchExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("用户🚀", "tok", "用户🚀", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("用户🚀", "tok", "用户🛰", false))
    }

    @Test
    fun longTokenDoesNotAffectStableSession() {
        val longToken = "x".repeat(10_000)
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", longToken, "u1", false))
    }

    @Test
    fun idsWithInternalSpacesMatch() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("user one", "tok", "user one", false))
    }

    @Test
    fun repeatedBatchChecksAreDeterministic() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1", false))
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1", false))
    }

    @Test
    fun userIdPrefixDoesNotMatchSibling() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u12", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u12", "tok", "u1", false))
    }

    @Test
    fun newlinePaddedExpectedIdDoesNotMatch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1\n", "tok", "u1", false))
    }

    @Test
    fun middleCaseDifferenceDoesNotMatch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("userA1", "tok", "usera1", false))
    }

    @Test
    fun batchOwnerIsPerCallNotSticky() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u2", false))
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u2", "tok", "u2", false))
    }

    @Test
    fun tokenWithInternalNewlineStillCountsAsLive() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok\nx", "u1", false))
    }

    @Test
    fun bothIdsBlankAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch(" ", "tok", " ", false))
    }

    @Test
    fun zeroWidthSpaceIdsMatchExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("\u200Bu1", "tok", "\u200Bu1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\u200Bu1", "tok", "u1", false))
    }

    @Test
    fun nonBreakingSpaceOnlyTokenAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "\u00A0", "u1", false))
    }

    @Test
    fun fullWidthSpacePaddedIdsMatchExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("\u3000u1\u3000", "tok", "\u3000u1\u3000", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\u3000u1\u3000", "tok", "u1", false))
    }

    @Test
    fun verticalTabOnlyIdsAbort() {
        // '\x0B' 是 Kotlin 认定的空白字符，纯纵向制表 id 视为空白
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\u000B", "tok", "\u000B", false))
    }

    @Test
    fun internalNewlineIdsMatchExactly() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u\n1", "tok", "u\n1", false))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u\n1", "tok", "u1", false))
    }

    @Test
    fun symmetricTrailingTabIdsStillMatch() {
        assertTrue(SenderKeyRetrySessionPolicy.mayContinueBatch("u1\t", "tok", "u1\t", false))
    }

    @Test
    fun formFeedOnlyBatchIdAborts() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("\u000C", "tok", "u1", false))
    }

    @Test
    fun purgeInProgressAbortsStableSession() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1", true))
    }
}
