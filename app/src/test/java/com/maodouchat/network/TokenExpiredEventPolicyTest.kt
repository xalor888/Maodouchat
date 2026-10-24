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
}
