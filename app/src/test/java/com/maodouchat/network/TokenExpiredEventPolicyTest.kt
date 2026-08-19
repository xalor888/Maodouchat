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
}
