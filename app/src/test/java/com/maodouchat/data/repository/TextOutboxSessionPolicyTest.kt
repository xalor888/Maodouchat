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
    fun `null live user aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = null,
            )
        )
    }

    @Test
    fun `whitespace-only token aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "   ",
                liveUserId = "u1",
            )
        )
    }

    @Test
    fun `whitespace-only live user aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = "   ",
            )
        )
    }

    @Test
    fun `padded live user does not match`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = " u1",
            )
        )
    }

    @Test
    fun `padded batch user does not match`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1 ",
                liveToken = "tok",
                liveUserId = "u1",
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

    @Test
    fun `whitespace-only batch user aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "   ",
                liveToken = "tok",
                liveUserId = "u1",
            )
        )
    }

    @Test
    fun `tab-newline token aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "\t\n",
                liveUserId = "u1",
            )
        )
    }

    @Test
    fun `user match is case-sensitive`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = "U1",
            )
        )
    }

    @Test
    fun `both token and user null aborts`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = null,
                liveUserId = null,
            )
        )
    }

    @Test
    fun `padded token with matching user continues`() {
        assertTrue(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = " tok ",
                liveUserId = "u1",
            )
        )
    }
}
