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
}
