package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScheduleModelsTest {

    private fun message() = ScheduledMessage(
        id = "s1",
        chatId = "c1",
        peerUserId = "u1",
        text = "hi",
        sendAtMillis = 1_000L,
        createdAtMillis = 500L,
    )

    private fun reminder() = MessageReminder(
        id = "r1",
        chatId = "c1",
        messageId = "m1",
        messagePreview = "hi",
        remindAtMillis = 2_000L,
        createdAtMillis = 500L,
        ownerUserId = "u1",
    )

    @Test
    fun scheduledMessageDefaults() {
        val item = message()
        assertFalse(item.isGroup)
        assertEquals("PENDING", item.status)
        assertEquals(0L, item.repeatIntervalMs)
        assertEquals(0, item.repeatCount)
        assertEquals(0, item.occurrencesSent)
        assertFalse(item.weekdaysOnly)
        assertEquals("hi", item.copy(text = "hi").text)
    }

    @Test
    fun chatScheduleCommandDefaults() {
        val command = ChatScheduleCommand(
            chatId = "c1",
            peerUserId = "u1",
            text = "hi",
            isGroup = false,
            sessionAvailable = true,
            blocked = false,
            sendAtMillis = 1_000L,
        )
        assertEquals(0L, command.repeatIntervalMs)
        assertEquals(0, command.repeatCount)
        assertFalse(command.weekdaysOnly)
    }

    @Test
    fun chatScheduleOutcomes() {
        val applied = ChatScheduleMutationOutcome.Applied(
            scheduledMessages = listOf(message()),
            effectiveAtMillis = 1_000L,
        )
        assertEquals(1, applied.scheduledMessages.size)
        val rejected = ChatScheduleMutationOutcome.Rejected(
            ChatScheduleRejection.BLOCKED,
        )
        assertEquals(ChatScheduleRejection.BLOCKED, rejected.reason)
        val ready = ChatScheduleImmediateOutcome.Ready(message())
        assertEquals("s1", ready.item.id)
        val scheduled = ChatReminderOutcome.Scheduled(reminder())
        assertEquals("r1", scheduled.reminder.id)
        val reminderRejected = ChatReminderOutcome.Rejected(
            ChatScheduleRejection.DISABLED,
        )
        assertEquals(ChatScheduleRejection.DISABLED, reminderRejected.reason)
    }

    @Test
    fun conversationScheduleResult() {
        val ok: ConversationScheduleResult<ScheduledMessage> =
            ConversationScheduleResult.Success(message())
        assertEquals("s1", (ok as ConversationScheduleResult.Success).value.id)
        val failed: ConversationScheduleResult<ScheduledMessage> =
            ConversationScheduleResult.Failure(
                ConversationScheduleFailure.STORAGE,
                cause = IllegalStateException("boom"),
            )
        assertEquals(
            ConversationScheduleFailure.STORAGE,
            (failed as ConversationScheduleResult.Failure).reason,
        )
        assertEquals("boom", failed.cause?.message)
    }

    @Test
    fun scheduleRequestsDefaults() {
        val request = ScheduledMessageRequest(
            chatId = "c1",
            peerUserId = "u1",
            text = "hi",
            sendAtMillis = 1_000L,
            isGroup = true,
        )
        assertEquals(0L, request.repeatIntervalMs)
        assertFalse(request.weekdaysOnly)
        val reminderRequest = MessageReminderRequest(
            chatId = "c1",
            messageId = "m1",
            messagePreview = "hi",
            remindAtMillis = 2_000L,
        )
        assertEquals("m1", reminderRequest.messageId)
    }

    @Test
    fun noRecipientDevicesExceptionIsIllegalState() {
        val error = NoRecipientDevicesException()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun attachmentSendAfterUploadRetryable() {
        assertTrue(
            AttachmentSendAfterUploadPolicy.isRetryable(
                NoRecipientDevicesException(),
            ),
        )
        assertTrue(AttachmentSendAfterUploadPolicy.isRetryable(IOException()))
        assertFalse(
            AttachmentSendAfterUploadPolicy.isRetryable(RuntimeException("boom")),
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(null),
        )
        assertFalse(
            AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(null),
        )
    }
}
