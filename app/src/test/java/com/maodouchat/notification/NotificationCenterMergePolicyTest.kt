package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun blankIncomingMessageIdFallsBackToItemId() {
        // incoming messageId 为空时回退到 item id 比对。
        assertTrue(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "msg_c1_m1",
                existingMessageId = "m1",
                incomingId = "msg_c1_m1",
                incomingMessageId = "",
            )
        )
        // 反过来：incomingId 为空但 messageId 命中，仍判重复。
        assertTrue(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "x",
                existingMessageId = "m1",
                incomingId = "",
                incomingMessageId = "m1",
            )
        )
    }

    @Test
    fun blankIncomingIdNeverMatchesByItemId() {
        assertFalse(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "msg_c1_m1",
                existingMessageId = "m1",
                incomingId = "",
                incomingMessageId = "m2",
            )
        )
    }

    @Test
    fun missedCallBlankIncomingCallIdFallsBackToItemId() {
        assertTrue(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "missed_call-1",
                existingCallId = "call-1",
                incomingId = "missed_call-1",
                incomingCallId = "",
            )
        )
        // callId 为空时不回退到 callId 比对：item id 不一致即判不同。
        assertFalse(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "missed_a",
                existingCallId = "call-1",
                incomingId = "missed_b",
                incomingCallId = "",
            )
        )
    }

    @Test
    fun stableExtraIsCaseSensitiveAndBlankSafe() {
        assertTrue(NotificationCenterMergePolicy.isSameStableExtra("r1", "r1"))
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", "R1"))
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", ""))
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", null))
    }

    @Test
    fun allBlankIdentifiersNeverMatch() {
        // 全空标识永远不等：不误把两条"无 id"行合并。
        assertFalse(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "",
                existingMessageId = null,
                incomingId = "",
                incomingMessageId = null
            )
        )
        assertFalse(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "msg_c1_m1",
                existingMessageId = "m1",
                incomingId = "",
                incomingMessageId = "   "
            )
        )
        assertFalse(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "",
                existingCallId = null,
                incomingId = "",
                incomingCallId = null
            )
        )
    }

    @Test
    fun nullExistingStableValueNeverMatchesNonBlankIncoming() {
        // 存量无值、来值非空：不等（防脏数据跨行吸附）。
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra(null, "r1"))
        assertFalse(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "x",
                existingMessageId = null,
                incomingId = "y",
                incomingMessageId = "m1"
            )
        )
        assertFalse(
            NotificationCenterMergePolicy.isSameMissedCallPayload(
                existingId = "missed_a",
                existingCallId = null,
                incomingId = "missed_b",
                incomingCallId = "sig-9"
            )
        )
    }

    @Test
    fun stableItemIdEqualitySkipsCountIncrement() {
        // item id 命中即跳过计数，即使 extra 里的稳定键不一致（item id 优先级）。
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MISSED_CALL",
                existingId = "missed_c1",
                existingExtra = mapOf("callId" to "c1"),
                incomingId = "missed_c1",
                incomingExtra = mapOf("callId" to "c2"),
            )
        )
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MESSAGE",
                existingId = "msg_c_m1",
                existingExtra = emptyMap(),
                incomingId = "msg_c_m1",
                incomingExtra = emptyMap(),
            )
        )
    }

    @Test
    fun stableExtraIsNotTrimmed() {
        // 精确匹配：不裁空白、不忽略大小写差异。
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra(" r1", "r1"))
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", "r1 "))
        assertFalse(
            NotificationCenterMergePolicy.isSameMessagePayload(
                existingId = "x",
                existingMessageId = " m1",
                incomingId = "y",
                incomingMessageId = "m1"
            )
        )
    }

    @Test
    fun mergeDecisionsAreDeterministic() {
        assertEquals(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MESSAGE",
                existingId = "x",
                existingExtra = mapOf("messageId" to "m1"),
                incomingId = "y",
                incomingExtra = mapOf("messageId" to "m1"),
            ),
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MESSAGE",
                existingId = "x",
                existingExtra = mapOf("messageId" to "m1"),
                incomingId = "y",
                incomingExtra = mapOf("messageId" to "m1"),
            )
        )
        assertEquals(
            NotificationCenterMergePolicy.isSameMissedCallPayload("missed_a", "c1", "missed_a", "c2"),
            NotificationCenterMergePolicy.isSameMissedCallPayload("missed_a", "c1", "missed_a", "c2")
        )
    }

    @Test
    fun skipCountIncrementForBlankIncomingExtrasAndUnknownType() {
        // MESSAGE：incomingId 为空但 messageId 命中仍跳过计数。
        assertTrue(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "MESSAGE",
                existingId = "x",
                existingExtra = mapOf("messageId" to "m1"),
                incomingId = "",
                incomingExtra = mapOf("messageId" to "m1"),
            )
        )
        // FRIEND_REQUEST：incoming requestId 为空不跳过。
        assertFalse(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "FRIEND_REQUEST",
                existingId = "friend_r1",
                existingExtra = mapOf("requestId" to "r1"),
                incomingId = "friend_push_r1_CREATED",
                incomingExtra = mapOf("requestId" to ""),
            )
        )
        // 未知类型永远递增。
        assertFalse(
            NotificationCenterMergePolicy.shouldSkipCountIncrement(
                itemType = "COMMENT",
                existingId = "post_1",
                existingExtra = emptyMap(),
                incomingId = "post_1",
                incomingExtra = emptyMap(),
            )
        )
    }
}
