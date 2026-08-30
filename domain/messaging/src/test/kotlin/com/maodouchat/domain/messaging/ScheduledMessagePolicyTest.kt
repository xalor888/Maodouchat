package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduledMessagePolicyTest {

    private fun msg(sendAt: Long, status: ScheduleStatus = ScheduleStatus.PENDING, recurrence: RecurrenceRule? = null) =
        ScheduledMessage(
            id = "s1",
            ownerUserId = "u1",
            conversationId = "c1",
            content = ContentPayload.Text("hi"),
            sendAtMillis = sendAt,
            status = status,
            recurrence = recurrence,
            idempotencyKey = "k1",
        )

    @Test
    fun `pending and due`() {
        assertTrue(ScheduledMessagePolicy.isDue(msg(1_000L), now = 2_000L))
    }

    @Test
    fun `pending but not due`() {
        assertFalse(ScheduledMessagePolicy.isDue(msg(3_000L), now = 2_000L))
    }

    @Test
    fun `sent or cancelled are not due`() {
        assertFalse(ScheduledMessagePolicy.isDue(msg(1_000L, ScheduleStatus.SENT), now = 2_000L))
        assertFalse(ScheduledMessagePolicy.isDue(msg(1_000L, ScheduleStatus.CANCELLED), now = 2_000L))
    }

    @Test
    fun `once recurrence returns null`() {
        assertNull(ScheduledMessagePolicy.nextRunAt(msg(1_000L, recurrence = RecurrenceRule(RecurrenceType.ONCE)), lastRunAt = 1_000L, now = 2_000L))
    }

    @Test
    fun `daily recurrence advances past now`() {
        val next = ScheduledMessagePolicy.nextRunAt(
            msg(0L, recurrence = RecurrenceRule(RecurrenceType.DAILY)),
            lastRunAt = 0L,
            now = 3L * 24 * 60 * 60 * 1_000L + 1,
        )
        assertEquals(4L * 24 * 60 * 60 * 1_000L, next)
    }
}
