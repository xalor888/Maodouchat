package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSendGuardModelsTest {

    @Test
    fun `send reject reasons are the five expected entries in order`() {
        assertEquals(
            listOf(
                SendRejectReason.ALREADY_SENDING,
                SendRejectReason.BLANK,
                SendRejectReason.NO_SESSION,
                SendRejectReason.BLOCKED,
                SendRejectReason.MENTION_EVERYONE_FORBIDDEN,
            ),
            SendRejectReason.entries.toList(),
        )
    }

    @Test
    fun `retry reject reasons are the five expected entries in order`() {
        assertEquals(
            listOf(
                RetryRejectReason.NOT_FOUND,
                RetryRejectReason.NOT_MY_MESSAGE,
                RetryRejectReason.NOT_FAILED,
                RetryRejectReason.NO_SESSION,
                RetryRejectReason.UNSUPPORTED_TYPE,
            ),
            RetryRejectReason.entries.toList(),
        )
    }

    @Test
    fun `valueOf resolves every entry name`() {
        for (reason in SendRejectReason.entries) {
            assertEquals(reason, SendRejectReason.valueOf(reason.name))
        }
        for (reason in RetryRejectReason.entries) {
            assertEquals(reason, RetryRejectReason.valueOf(reason.name))
        }
    }
}
