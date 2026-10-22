package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NotificationSlotPolicyTest {

    @Test
    fun messageSlotIsPerChatWithFixedZeroId() {
        assertEquals("maodouchat_c1", NotificationSlotPolicy.messageTag("c1"))
        assertEquals(0, NotificationSlotPolicy.messageNotifyId())
        assertNotEquals(
            NotificationSlotPolicy.messageTag("c1"),
            NotificationSlotPolicy.messageTag("c2")
        )
    }

    @Test
    fun reminderSlotDerivesFromChatAndMessage() {
        assertEquals("maodouchat_reminder_c1", NotificationSlotPolicy.reminderTag("c1"))
        assertEquals("m1".hashCode(), NotificationSlotPolicy.reminderNotifyId("m1"))
        assertEquals("reminder_c1".hashCode(), NotificationSlotPolicy.reminderRequestCode("c1"))
    }

    @Test
    fun incomingAndMissedIdsAreIsolated() {
        val callId = "call-123"
        // 8.44 回归：来电/未接 id 空间必须隔离，cancelIncoming 永不擦掉未接条目。
        assertNotEquals(
            NotificationSlotPolicy.incomingCallNotifyId(callId),
            NotificationSlotPolicy.missedCallNotifyId(callId)
        )
        assertEquals(callId.hashCode(), NotificationSlotPolicy.incomingCallNotifyId(callId))
        assertEquals(
            callId.hashCode() xor NotificationSlotPolicy.MISSED_CALL_ID_SALT,
            NotificationSlotPolicy.missedCallNotifyId(callId)
        )
        assertEquals("maodouchat_call", NotificationSlotPolicy.CALL_TAG)
        assertEquals("maodouchat_missed", NotificationSlotPolicy.MISSED_CALL_TAG)
    }

    @Test
    fun postSlotUsesPrefixedHash() {
        assertEquals("post_p1".hashCode(), NotificationSlotPolicy.postNotifyId("p1"))
        assertEquals("maodouchat_post", NotificationSlotPolicy.POST_TAG)
    }

    @Test
    fun aiTaskGroupingContract() {
        assertEquals("maodouchat_ai_task", NotificationSlotPolicy.AI_TASK_TAG)
        assertEquals("t1".hashCode(), NotificationSlotPolicy.aiTaskNotifyId("t1"))
        assertEquals("ai_tasks_c1", NotificationSlotPolicy.aiTaskGroupKey("c1"))
        assertEquals("ai_summary_c1".hashCode(), NotificationSlotPolicy.aiTaskSummaryId("c1"))
    }

    @Test
    fun socialTagsAreFrozen() {
        assertEquals("maodouchat_friend_request", NotificationSlotPolicy.FRIEND_REQUEST_TAG)
        assertEquals("maodouchat_group_invite", NotificationSlotPolicy.GROUP_INVITE_TAG)
        assertEquals("maodouchat_announcement", NotificationSlotPolicy.ANNOUNCEMENT_TAG)
        assertTrue(
            setOf(
                NotificationSlotPolicy.AI_TASK_TAG,
                NotificationSlotPolicy.FRIEND_REQUEST_TAG,
                NotificationSlotPolicy.GROUP_INVITE_TAG,
                NotificationSlotPolicy.ANNOUNCEMENT_TAG,
                NotificationSlotPolicy.CALL_TAG,
                NotificationSlotPolicy.MISSED_CALL_TAG,
                NotificationSlotPolicy.POST_TAG,
                NotificationSlotPolicy.TEST_TAG,
            ).size == 8,
            "tags must stay mutually distinct"
        )
    }

    @Test
    fun dataUrisAreFrozen() {
        assertEquals("maodouchat-notify://chat/c1", NotificationSlotPolicy.chatDataUri("c1"))
        assertEquals(
            "maodouchat-notify://reminder/c1/m1",
            NotificationSlotPolicy.reminderDataUri("c1", "m1")
        )
        assertEquals(
            "maodouchat-notify-reply://chat/c1",
            NotificationSlotPolicy.quickReplyDataUri("c1")
        )
        assertEquals(
            "maodouchat-notify://incoming/call1",
            NotificationSlotPolicy.incomingCallDataUri("call1")
        )
        assertEquals(
            "maodouchat-notify://missed/call1",
            NotificationSlotPolicy.missedCallDataUri("call1")
        )
        assertEquals("maodouchat-notify://post/p1", NotificationSlotPolicy.postDataUri("p1"))
        assertEquals(
            "maodouchat-notify://aitask/t1",
            NotificationSlotPolicy.aiTaskDataUri("t1")
        )
        assertEquals(
            "maodouchat-notify://friend/r1",
            NotificationSlotPolicy.friendRequestDataUri("r1")
        )
        assertEquals(
            "maodouchat-notify://group-invite/i1",
            NotificationSlotPolicy.groupInviteDataUri("i1")
        )
        assertEquals(
            "maodouchat-notify://announcement/a1",
            NotificationSlotPolicy.announcementDataUri("a1")
        )
    }

    @Test
    fun requestCodesAreFrozen() {
        assertEquals("c1".hashCode(), NotificationSlotPolicy.chatRequestCode("c1"))
        assertEquals("c1".hashCode() + 1, NotificationSlotPolicy.markReadRequestCode("c1"))
        assertEquals("c1".hashCode() + 2, NotificationSlotPolicy.quickReplyRequestCode("c1"))
        assertEquals(
            "maodouchat-notify-read://chat/c1",
            NotificationSlotPolicy.markReadDataUri("c1")
        )
        assertEquals("friend_r1".hashCode(), NotificationSlotPolicy.friendRequestRequestCode("r1"))
        assertEquals(
            "group_invite_i1".hashCode(),
            NotificationSlotPolicy.groupInviteRequestCode("i1")
        )
        assertEquals(
            "announcement_a1".hashCode(),
            NotificationSlotPolicy.announcementRequestCode("a1")
        )
        assertEquals(
            "scheduled_message_failed".hashCode(),
            NotificationSlotPolicy.scheduledMessageFailedNotifyId()
        )
    }

    @Test
    fun reminderSpaceIsIsolatedFromMessageSpace() {
        // 稍后提醒与会话消息的 tag/id/requestCode 必须互不重叠，清理一边永不擦掉另一边。
        assertNotEquals(
            NotificationSlotPolicy.messageTag("c1"),
            NotificationSlotPolicy.reminderTag("c1")
        )
        assertNotEquals(
            NotificationSlotPolicy.messageNotifyId(),
            NotificationSlotPolicy.reminderNotifyId("m1")
        )
        assertNotEquals(
            NotificationSlotPolicy.chatRequestCode("c1"),
            NotificationSlotPolicy.reminderRequestCode("c1")
        )
    }

    @Test
    fun chatActionRequestCodesAreDistinct() {
        // 同一会话的三种动作 requestCode 按 +1/+2 错开，互不踩踏。
        val chat = NotificationSlotPolicy.chatRequestCode("c1")
        assertEquals(chat + 1, NotificationSlotPolicy.markReadRequestCode("c1"))
        assertEquals(chat + 2, NotificationSlotPolicy.quickReplyRequestCode("c1"))
        assertNotEquals(
            NotificationSlotPolicy.markReadRequestCode("c1"),
            NotificationSlotPolicy.quickReplyRequestCode("c1")
        )
    }

    @Test
    fun postNotifyIdIsIsolatedFromPostRequestCode() {
        // 动态通知 id 用 "post_" 前缀哈希，requestCode 用裸 id 哈希：两空间隔离。
        assertNotEquals(
            NotificationSlotPolicy.postNotifyId("p1"),
            NotificationSlotPolicy.postRequestCode("p1")
        )
        assertEquals("p1".hashCode(), NotificationSlotPolicy.postRequestCode("p1"))
    }

    @Test
    fun slotFunctionsAreDeterministic() {
        // 纯函数：重复调用结果恒定，钉死"无隐式状态"契约。
        repeat(2) {
            assertEquals("maodouchat_c1", NotificationSlotPolicy.messageTag("c1"))
            assertEquals(0, NotificationSlotPolicy.messageNotifyId())
            assertEquals("maodouchat_reminder_c1", NotificationSlotPolicy.reminderTag("c1"))
            assertEquals(
                "call-1".hashCode(),
                NotificationSlotPolicy.incomingCallNotifyId("call-1")
            )
            assertEquals(
                "call-1".hashCode() xor NotificationSlotPolicy.MISSED_CALL_ID_SALT,
                NotificationSlotPolicy.missedCallNotifyId("call-1")
            )
            assertEquals("ai_tasks_c1", NotificationSlotPolicy.aiTaskGroupKey("c1"))
        }
    }

    @Test
    fun emptyInputsProduceStableDegenerateSlots() {
        // 空 id 不抛异常，槽位字符串仍按前缀规则拼出且互不相同。
        assertEquals("maodouchat_", NotificationSlotPolicy.messageTag(""))
        assertEquals("maodouchat_reminder_", NotificationSlotPolicy.reminderTag(""))
        assertNotEquals(
            NotificationSlotPolicy.messageTag(""),
            NotificationSlotPolicy.reminderTag("")
        )
        assertEquals(
            "maodouchat-notify://chat/",
            NotificationSlotPolicy.chatDataUri("")
        )
    }
}
