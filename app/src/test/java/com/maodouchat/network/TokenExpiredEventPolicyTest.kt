package com.maodouchat.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenExpiredEventPolicyTest {

    @Test
    fun matchingOwnerAndSessionGenerationIsHandled() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1", 7L))
    }

    @Test
    fun replayFromPurgedSessionCannotClearSameUsersNewLogin() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1", 8L))
    }

    @Test
    fun eventCannotClearAnotherAccount() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u2", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("", 7L, "", 7L))
    }

    @Test
    fun nullCurrentOwnerIsNeverHandled() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, null, 7L))
    }

    @Test
    fun blankEventOwnerWithNonBlankCurrentIsRejected() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("", 7L, "u1", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("   ", 7L, "u1", 7L))
    }

    @Test
    fun olderGenerationReplayIsRejected() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 6L, "u1", 7L))
    }

    @Test
    fun zeroGenerationsStillMatch() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u1", 0L, "u1", 0L))
    }

    @Test
    fun ownerMatchIsCaseSensitive() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "U1", 7L))
    }

    @Test
    fun negativeGenerationMatchesExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u1", -1L, "u1", -1L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", -1L, "u1", 0L))
    }

    @Test
    fun maxLongGenerationMatches() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u1", Long.MAX_VALUE, "u1", Long.MAX_VALUE))
    }

    @Test
    fun newerGenerationReplayIsRejected() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 8L, "u1", 7L))
    }

    @Test
    fun blankCurrentOwnerIsNeverHandled() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "   ", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("", 7L, null, 7L))
    }

    @Test
    fun tabOnlyEventOwnerIsRejected() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("\t", 7L, "u1", 7L))
    }

    @Test
    fun minLongGenerationMatchesExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u1", Long.MIN_VALUE, "u1", Long.MIN_VALUE))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", Long.MIN_VALUE, "u1", Long.MAX_VALUE))
    }

    @Test
    fun sameInputHandledStablyAcrossCalls() {
        repeat(3) {
            assertTrue(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1", 7L))
            assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1", 8L))
        }
    }

    @Test
    fun trailingWhitespaceOwnerMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1 ", 7L, "u1", 7L))
    }

    @Test
    fun unicodeOwnerMatchedExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("用户1", 7L, "用户1", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("用户1", 7L, "用户2", 7L))
    }

    @Test
    fun nullCurrentOwnerNeverHandledForAnyGeneration() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 0L, null, 0L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", -5L, null, -5L))
    }

    @Test
    fun leadingWhitespaceEventOwnerMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle(" u1", 7L, "u1", 7L))
    }

    @Test
    fun currentOwnerTrailingSpaceMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1 ", 7L))
    }

    @Test
    fun newlineSuffixedEventOwnerMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1\n", 7L, "u1", 7L))
    }

    @Test
    fun zeroVsNegativeGenerationMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 0L, "u1", -1L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", -1L, "u1", 0L))
    }

    @Test
    fun maxGenerationOffByOneMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", Long.MAX_VALUE, "u1", Long.MAX_VALUE - 1L))
    }

    @Test
    fun newlineOnlyEventOwnerRejected() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("\n", 7L, "u1", 7L))
    }

    @Test
    fun blankEventOwnerWithNullCurrentRejected() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("", 7L, null, 7L))
    }

    @Test
    fun internalSpaceOwnerMatchesExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u 1", 7L, "u 1", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u 1", 7L, "u1", 7L))
    }

    @Test
    fun emojiOwnerMatchesExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u👍", 7L, "u👍", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u👍", 7L, "u👎", 7L))
    }

    @Test
    fun currentOwnerTrailingTabMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1\t", 7L))
    }

    @Test
    fun carriageReturnSuffixedEventOwnerMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1\r", 7L, "u1", 7L))
    }

    @Test
    fun carriageReturnSuffixedCurrentOwnerMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1\r", 7L))
    }

    @Test
    fun internalTabOwnerMatchesExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("u\t1", 7L, "u\t1", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u\t1", 7L, "u1", 7L))
    }

    @Test
    fun upperCaseOwnerMatchesExactly() {
        assertTrue(TokenExpiredEventPolicy.shouldHandle("U1", 7L, "U1", 7L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("U1", 7L, "u1", 7L))
    }

    @Test
    fun generationDiffersByTwoMismatch() {
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1", 9L))
        assertFalse(TokenExpiredEventPolicy.shouldHandle("u1", 7L, "u1", 5L))
    }
}
