package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationCenterMergePolicyTest {

    @Test
    fun sameMessageIdIsDuplicate() {
        assertTrue(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "msg_c1_m1",
                existingMessageId = "m1",
                incomingId = "msg_c1_m1",
                incomingMessageId = "m1"
            )
        )
    }

    @Test
    fun sameItemIdIsDuplicate() {
        assertTrue(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "msg_c1_m1",
                existingMessageId = null,
                incomingId = "msg_c1_m1",
                incomingMessageId = null
            )
        )
    }

    @Test
    fun differentMessageIncrements() {
        assertFalse(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "msg_c1_m1",
                existingMessageId = "m1",
                incomingId = "msg_c1_m2",
                incomingMessageId = "m2"
            )
        )
    }

    @Test
    fun sameMissedCallIdIsDuplicate() {
        assertTrue(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "missed_call-1",
                existingCallId = "call-1",
                incomingId = "missed_call-1",
                incomingCallId = "call-1",
            )
        )
    }

    @Test
    fun sameCallIdDifferentItemIdIsDuplicate() {
        // Unlikely after stable ids, but extra.callId is the durable key.
        assertTrue(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "missed_a",
                existingCallId = "sig-9",
                incomingId = "missed_b",
                incomingCallId = "sig-9",
            )
        )
    }

    @Test
    fun differentMissedCallsIncrement() {
        assertFalse(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "missed_c1",
                existingCallId = "c1",
                incomingId = "missed_c2",
                incomingCallId = "c2",
            )
        )
    }

    @Test
    fun shouldSkipCountForStableNotificationIdentities() {
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MESSAGE",
                existingId = "msg_c_m1",
                existingExtra = mapOf("messageId" to "m1"),
                incomingId = "msg_c_m1",
                incomingExtra = mapOf("messageId" to "m1"),
            )
        )
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MISSED_CALL",
                existingId = "missed_x",
                existingExtra = mapOf("callId" to "x"),
                incomingId = "missed_x",
                incomingExtra = mapOf("callId" to "x"),
            )
        )
        assertFalse(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "POST_INTERACTION",
                existingId = "post_1",
                existingExtra = emptyMap(),
                incomingId = "post_1",
                incomingExtra = emptyMap(),
            )
        )
    }

    @Test
    fun stableFriendRequestAndAiTaskIdsAreDeduplicated() {
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "FRIEND_REQUEST",
                existingId = "friend_r1",
                existingExtra = mapOf("requestId" to "r1"),
                incomingId = "friend_push_r1_CREATED",
                incomingExtra = mapOf("requestId" to "r1"),
            )
        )
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "AI_TASK",
                existingId = "ai_task_t1",
                existingExtra = mapOf("taskId" to "t1"),
                incomingId = "ai_task_t1",
                incomingExtra = mapOf("taskId" to "t1"),
            )
        )
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", "r2"))
    }
}
