package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageTimelineGroupingPolicyTest {

    private fun message(id: String, senderId: String, timestamp: Long): Message =
        Message(id = id, chatId = "c1", senderId = senderId, content = "x", type = MessageType.TEXT, timestamp = timestamp)

    @Test
    fun sameSenderZeroGapIsGrouped() {
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_000L),
                message("m1", "u1", 1_000_000L),
            )
        )
    }

    @Test
    fun sameSenderJustInsideWindowIsGrouped() {
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_359_999L),
                message("m1", "u1", 1_000_000L),
            )
        )
    }

    @Test
    fun sameSenderExactBoundaryNotGrouped() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_360_000L),
                message("m1", "u1", 1_000_000L),
            )
        )
    }

    @Test
    fun sameSenderBeyondWindowNotGrouped() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_360_001L),
                message("m1", "u1", 1_000_000L),
            )
        )
    }

    @Test
    fun differentSenderZeroGapNotGrouped() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u2", 1_000_000L),
                message("m1", "u1", 1_000_000L),
            )
        )
    }

    @Test
    fun previousNewerWithinWindowIsGrouped() {
        // reverseLayout 下调用点传的是时间上更早的 previous，绝对值使顺序无关。
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_000L),
                message("m1", "u1", 1_200_000L),
            )
        )
    }

    @Test
    fun previousNewerBeyondWindowNotGrouped() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_000L),
                message("m1", "u1", 1_400_000L),
            )
        )
    }

    @Test
    fun customWindowIsRespected() {
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_030_000L),
                message("m1", "u1", 1_000_000L),
                windowMs = 60_000L,
            )
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_120_000L),
                message("m1", "u1", 1_000_000L),
                windowMs = 60_000L,
            )
        )
    }
}
