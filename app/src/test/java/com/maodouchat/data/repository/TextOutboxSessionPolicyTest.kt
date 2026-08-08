package com.maodouchat.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOutboxSessionPolicyTest {
    @Test
    fun `same user with token continues`() {
        assertTrue(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = "u1",
            )
        )
    }

    @Test
    fun `logout clears token aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = null,
                liveUserId = "u1",
            )
        )
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "",
                liveUserId = "u1",
            )
        )
    }

    @Test
    fun `account switch aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = "u2",
            )
        )
    }

    @Test
    fun `blank batch user aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "",
                liveToken = "tok",
                liveUserId = "u1",
            )
        )
    }
}
