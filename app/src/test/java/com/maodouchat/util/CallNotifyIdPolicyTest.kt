package com.maodouchat.util

import com.maodouchat.notification.NotificationSlotPolicy
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals

/**
 * Incoming vs missed trays must not share NotificationManager ids, otherwise
 * cancelIncomingCall after MissedCallRecorder would erase the missed shade entry.
 */
class CallNotifyIdPolicyTest {

    private val missedSalt = 0x4D495353

    private fun incomingId(callId: String): Int = callId.hashCode()
    private fun missedId(callId: String): Int = callId.hashCode() xor missedSalt

    @Test
    fun incomingAndMissedIdsDifferForSameCall() {
        val callId = "sig-call-42"
        assertNotEquals(incomingId(callId), missedId(callId))
    }

    @Test
    fun stableAcrossCalls() {
        assertEquals(incomingId("a"), incomingId("a"))
        assertEquals(missedId("a"), missedId("a"))
        assertNotEquals(missedId("a"), missedId("b"))
    }

    @Test
    fun productionIncomingAndMissedIdsStayIsolated() {
        val callId = "sig-call-42"
        assertNotEquals(
            NotificationSlotPolicy.incomingCallNotifyId(callId),
            NotificationSlotPolicy.missedCallNotifyId(callId)
        )
    }

    @Test
    fun missedSaltIsPinnedToMissAscii() {
        assertEquals(0x4D495353, NotificationSlotPolicy.MISSED_CALL_ID_SALT)
        val callId = "sig-call-42"
        assertEquals(
            callId.hashCode() xor 0x4D495353,
            NotificationSlotPolicy.missedCallNotifyId(callId)
        )
    }

    @Test
    fun missedIdsAreDistinctAcrossCalls() {
        val ids = listOf("a", "b", "sig-call-42").map { NotificationSlotPolicy.missedCallNotifyId(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun callAndMissedDataUrisAreNamespaced() {
        assertEquals(
            "maodouchat-notify://incoming/x",
            NotificationSlotPolicy.incomingCallDataUri("x")
        )
        assertEquals(
            "maodouchat-notify://missed/x",
            NotificationSlotPolicy.missedCallDataUri("x")
        )
        assertNotEquals(
            NotificationSlotPolicy.incomingCallDataUri("x"),
            NotificationSlotPolicy.missedCallDataUri("x")
        )
    }

    @Test
    fun callAndMissedTagsArePinned() {
        assertEquals("maodouchat_call", NotificationSlotPolicy.CALL_TAG)
        assertEquals("maodouchat_missed", NotificationSlotPolicy.MISSED_CALL_TAG)
    }

    @Test
    fun messageTagUsesPrefixAndFixedZeroId() {
        assertEquals("maodouchat_", NotificationSlotPolicy.MESSAGE_TAG_PREFIX)
        assertEquals("maodouchat_c1", NotificationSlotPolicy.messageTag("c1"))
        assertEquals("maodouchat_", NotificationSlotPolicy.messageTag(""))
        assertEquals(0, NotificationSlotPolicy.messageNotifyId())
    }

    @Test
    fun reminderSlotsAreNamespacedFromChatSlots() {
        assertEquals("maodouchat_reminder_c1", NotificationSlotPolicy.reminderTag("c1"))
        assertEquals("m1".hashCode(), NotificationSlotPolicy.reminderNotifyId("m1"))
        assertEquals("reminder_c1".hashCode(), NotificationSlotPolicy.reminderRequestCode("c1"))
        assertNotEquals(
            NotificationSlotPolicy.reminderRequestCode("c1"),
            NotificationSlotPolicy.chatRequestCode("c1")
        )
    }

    @Test
    fun chatActionRequestCodesAreOffsetByOneAndTwo() {
        assertEquals(120, NotificationSlotPolicy.chatRequestCode("x"))
        assertEquals(121, NotificationSlotPolicy.markReadRequestCode("x"))
        assertEquals(122, NotificationSlotPolicy.quickReplyRequestCode("x"))
        assertEquals("maodouchat-notify://chat/x", NotificationSlotPolicy.chatDataUri("x"))
        assertEquals("maodouchat-notify-read://chat/x", NotificationSlotPolicy.markReadDataUri("x"))
        assertEquals("maodouchat-notify-reply://chat/x", NotificationSlotPolicy.quickReplyDataUri("x"))
        assertEquals(
            "maodouchat-notify://reminder/c1/m1",
            NotificationSlotPolicy.reminderDataUri("c1", "m1")
        )
    }

    @Test
    fun postAndAiTaskSlots() {
        assertEquals("maodouchat_post", NotificationSlotPolicy.POST_TAG)
        assertEquals("maodouchat_ai_task", NotificationSlotPolicy.AI_TASK_TAG)
        assertEquals("post_p".hashCode(), NotificationSlotPolicy.postNotifyId("p"))
        assertEquals(112, NotificationSlotPolicy.postRequestCode("p"))
        assertNotEquals(
            NotificationSlotPolicy.postNotifyId("p"),
            NotificationSlotPolicy.postRequestCode("p")
        )
        assertEquals("maodouchat-notify://post/p", NotificationSlotPolicy.postDataUri("p"))
        assertEquals(116, NotificationSlotPolicy.aiTaskNotifyId("t"))
        assertEquals(
            NotificationSlotPolicy.aiTaskNotifyId("t"),
            NotificationSlotPolicy.aiTaskRequestCode("t")
        )
        assertEquals("ai_tasks_c", NotificationSlotPolicy.aiTaskGroupKey("c"))
        assertEquals("ai_summary_c".hashCode(), NotificationSlotPolicy.aiTaskSummaryId("c"))
        assertEquals("maodouchat-notify://aitask/t", NotificationSlotPolicy.aiTaskDataUri("t"))
    }

    @Test
    fun socialAndScheduledSlots() {
        assertEquals("maodouchat_friend_request", NotificationSlotPolicy.FRIEND_REQUEST_TAG)
        assertEquals("maodouchat_group_invite", NotificationSlotPolicy.GROUP_INVITE_TAG)
        assertEquals("maodouchat_announcement", NotificationSlotPolicy.ANNOUNCEMENT_TAG)
        assertEquals("maodouchat_test", NotificationSlotPolicy.TEST_TAG)
        assertEquals(114, NotificationSlotPolicy.friendRequestNotifyId("r"))
        assertEquals("friend_r".hashCode(), NotificationSlotPolicy.friendRequestRequestCode("r"))
        assertEquals("maodouchat-notify://friend/r", NotificationSlotPolicy.friendRequestDataUri("r"))
        assertEquals(103, NotificationSlotPolicy.groupInviteNotifyId("g"))
        assertEquals("group_invite_g".hashCode(), NotificationSlotPolicy.groupInviteRequestCode("g"))
        assertEquals("maodouchat-notify://group-invite/g", NotificationSlotPolicy.groupInviteDataUri("g"))
        assertEquals(97, NotificationSlotPolicy.announcementNotifyId("a"))
        assertEquals("announcement_a".hashCode(), NotificationSlotPolicy.announcementRequestCode("a"))
        assertEquals(
            "maodouchat-notify://announcement/a",
            NotificationSlotPolicy.announcementDataUri("a")
        )
        assertEquals(
            "scheduled_message_failed".hashCode(),
            NotificationSlotPolicy.scheduledMessageFailedNotifyId()
        )
    }
}
