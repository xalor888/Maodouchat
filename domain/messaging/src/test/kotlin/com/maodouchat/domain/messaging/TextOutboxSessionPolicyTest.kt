package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextOutboxSessionPolicyTest {
    @Test
    fun `same user with token continues`() {
        assertTrue(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = "u1",
                purgeInProgress = false,
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
                purgeInProgress = false,
            )
        )
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "",
                liveUserId = "u1",
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
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
                purgeInProgress = false,
            )
        )
    }

    @Test
    fun `purge in progress aborts stable session`() {
        assertFalse(
            TextOutboxSessionPolicy.mayContinueFlush(
                batchUserId = "u1",
                liveToken = "tok",
                liveUserId = "u1",
                purgeInProgress = true,
            )
        )
    }
}
