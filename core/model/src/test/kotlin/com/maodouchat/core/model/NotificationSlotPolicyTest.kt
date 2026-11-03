package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationSlotPolicyTest {

    @Test
    fun `message slot is fixed id zero with chat tag`() {
        assertEquals("maodouchat_chat1", NotificationSlotPolicy.messageTag("chat1"))
        assertEquals(0, NotificationSlotPolicy.messageNotifyId())
    }

    @Test
    fun `missed id is isolated from incoming id`() {
        assertNotEquals(
            NotificationSlotPolicy.incomingCallNotifyId("c1"),
            NotificationSlotPolicy.missedCallNotifyId("c1")
        )
    }

    @Test
    fun `ai task group key is per chat`() {
        assertEquals("ai_tasks_chat9", NotificationSlotPolicy.aiTaskGroupKey("chat9"))
        assertEquals(
            "ai_summary_chat9".hashCode(),
            NotificationSlotPolicy.aiTaskSummaryId("chat9")
        )
    }
}
