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
    fun incomingAndMissedIdsFollowHashCodeXorSaltIdentity() {
        listOf("sig-call-42", "call-1", "x").forEach { callId ->
            assertEquals(callId.hashCode(), NotificationSlotPolicy.incomingCallNotifyId(callId))
            assertEquals(
                callId.hashCode() xor NotificationSlotPolicy.MISSED_CALL_ID_SALT,
                NotificationSlotPolicy.missedCallNotifyId(callId)
            )
            assertEquals(
                NotificationSlotPolicy.incomingCallNotifyId(callId) xor NotificationSlotPolicy.MISSED_CALL_ID_SALT,
                NotificationSlotPolicy.missedCallNotifyId(callId)
            )
        }
    }

    @Test
    fun emptyCallIdMapsToZeroAndSalt() {
        assertEquals(0, NotificationSlotPolicy.incomingCallNotifyId(""))
        assertEquals(
            NotificationSlotPolicy.MISSED_CALL_ID_SALT,
            NotificationSlotPolicy.missedCallNotifyId("")
        )
    }

    @Test
    fun productionSlotIdsAreDeterministicAcrossCalls() {
        repeat(2) {
            assertEquals(
                NotificationSlotPolicy.incomingCallNotifyId("c1"),
                NotificationSlotPolicy.incomingCallNotifyId("c1")
            )
            assertEquals(
                NotificationSlotPolicy.missedCallNotifyId("c1"),
                NotificationSlotPolicy.missedCallNotifyId("c1")
            )
            assertEquals(
                NotificationSlotPolicy.reminderNotifyId("m1"),
                NotificationSlotPolicy.reminderNotifyId("m1")
            )
            assertEquals(
                NotificationSlotPolicy.chatRequestCode("c1"),
                NotificationSlotPolicy.chatRequestCode("c1")
            )
            assertEquals(
                NotificationSlotPolicy.aiTaskNotifyId("t"),
                NotificationSlotPolicy.aiTaskNotifyId("t")
            )
            assertEquals(
                NotificationSlotPolicy.scheduledMessageFailedNotifyId(),
                NotificationSlotPolicy.scheduledMessageFailedNotifyId()
            )
        }
    }

    @Test
    fun multiCharNotifyIdsFollowTheirFormulas() {
        assertEquals("req-1".hashCode(), NotificationSlotPolicy.friendRequestNotifyId("req-1"))
        assertEquals("inv-2".hashCode(), NotificationSlotPolicy.groupInviteNotifyId("inv-2"))
        assertEquals("ann-3".hashCode(), NotificationSlotPolicy.announcementNotifyId("ann-3"))
        assertEquals("post_p9".hashCode(), NotificationSlotPolicy.postNotifyId("p9"))
        assertEquals("p9".hashCode(), NotificationSlotPolicy.postRequestCode("p9"))
        assertEquals("ai_summary_c9".hashCode(), NotificationSlotPolicy.aiTaskSummaryId("c9"))
        assertNotEquals(
            NotificationSlotPolicy.postNotifyId("p9"),
            NotificationSlotPolicy.postRequestCode("p9")
        )
    }

    @Test
    fun chatActionOffsetsHoldForAnotherChat() {
        assertEquals("c2".hashCode(), NotificationSlotPolicy.chatRequestCode("c2"))
        assertEquals("c2".hashCode() + 1, NotificationSlotPolicy.markReadRequestCode("c2"))
        assertEquals("c2".hashCode() + 2, NotificationSlotPolicy.quickReplyRequestCode("c2"))
        assertEquals("reminder_c2".hashCode(), NotificationSlotPolicy.reminderRequestCode("c2"))
        assertNotEquals(
            NotificationSlotPolicy.reminderRequestCode("c2"),
            NotificationSlotPolicy.chatRequestCode("c2")
        )
        assertEquals(
            "maodouchat-notify://chat/c2",
            NotificationSlotPolicy.chatDataUri("c2")
        )
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

    @Test
    fun reminderAndChatNamespacesStaySeparateForSameId() {
        assertNotEquals(
            NotificationSlotPolicy.messageTag("c9"),
            NotificationSlotPolicy.reminderTag("c9")
        )
        assertNotEquals(
            NotificationSlotPolicy.chatRequestCode("c9"),
            NotificationSlotPolicy.reminderRequestCode("c9")
        )
        assertEquals(
            "maodouchat_reminder_c9",
            NotificationSlotPolicy.reminderTag("c9")
        )
        assertEquals(
            "m9".hashCode(),
            NotificationSlotPolicy.reminderNotifyId("m9")
        )
        assertNotEquals(
            NotificationSlotPolicy.messageNotifyId(),
            NotificationSlotPolicy.reminderNotifyId("m9")
        )
    }

    @Test
    fun emptyCallIdDataUrisKeepIncomingAndMissedApart() {
        assertEquals(
            "maodouchat-notify://incoming/",
            NotificationSlotPolicy.incomingCallDataUri("")
        )
        assertEquals(
            "maodouchat-notify://missed/",
            NotificationSlotPolicy.missedCallDataUri("")
        )
        assertNotEquals(
            NotificationSlotPolicy.incomingCallDataUri(""),
            NotificationSlotPolicy.missedCallDataUri("")
        )
    }

    @Test
    fun aiTaskNotifyEqualsRequestAndGroupKeysAreChatScoped() {
        listOf("t2", "task-x").forEach { taskId ->
            assertEquals(
                NotificationSlotPolicy.aiTaskNotifyId(taskId),
                NotificationSlotPolicy.aiTaskRequestCode(taskId)
            )
        }
        assertEquals("ai_tasks_", NotificationSlotPolicy.aiTaskGroupKey(""))
        assertEquals(
            "ai_summary_c9".hashCode(),
            NotificationSlotPolicy.aiTaskSummaryId("c9")
        )
        assertNotEquals(
            NotificationSlotPolicy.aiTaskSummaryId("c9"),
            NotificationSlotPolicy.aiTaskSummaryId("c2")
        )
    }

    @Test
    fun socialRequestCodesFollowPrefixFormulasForNewIds() {
        assertEquals("req-9".hashCode(), NotificationSlotPolicy.friendRequestNotifyId("req-9"))
        assertEquals("friend_req-9".hashCode(), NotificationSlotPolicy.friendRequestRequestCode("req-9"))
        assertEquals("inv-9".hashCode(), NotificationSlotPolicy.groupInviteNotifyId("inv-9"))
        assertEquals("group_invite_inv-9".hashCode(), NotificationSlotPolicy.groupInviteRequestCode("inv-9"))
        assertEquals("ann-9".hashCode(), NotificationSlotPolicy.announcementNotifyId("ann-9"))
        assertEquals(
            "announcement_ann-9".hashCode(),
            NotificationSlotPolicy.announcementRequestCode("ann-9")
        )
    }

    @Test
    fun postNotifyAndRequestCodesStayIsolatedForNewId() {
        assertEquals("post_z".hashCode(), NotificationSlotPolicy.postNotifyId("z"))
        assertEquals("z".hashCode(), NotificationSlotPolicy.postRequestCode("z"))
        assertNotEquals(
            NotificationSlotPolicy.postNotifyId("z"),
            NotificationSlotPolicy.postRequestCode("z")
        )
        assertNotEquals(
            NotificationSlotPolicy.scheduledMessageFailedNotifyId(),
            NotificationSlotPolicy.messageNotifyId()
        )
        assertEquals(
            NotificationSlotPolicy.scheduledMessageFailedNotifyId(),
            NotificationSlotPolicy.scheduledMessageFailedNotifyId()
        )
    }

    @Test
    fun socialDataUrisFollowTheirFormulasForNewIds() {
        assertEquals(
            "maodouchat-notify://friend/req-9",
            NotificationSlotPolicy.friendRequestDataUri("req-9")
        )
        assertEquals(
            "maodouchat-notify://group-invite/inv-9",
            NotificationSlotPolicy.groupInviteDataUri("inv-9")
        )
        assertEquals(
            "maodouchat-notify://announcement/ann-9",
            NotificationSlotPolicy.announcementDataUri("ann-9")
        )
    }

    @Test
    fun missedSaltXorIsItsOwnInverse() {
        listOf("sig-call-42", "").forEach { callId ->
            assertEquals(
                NotificationSlotPolicy.incomingCallNotifyId(callId),
                NotificationSlotPolicy.missedCallNotifyId(callId) xor
                    NotificationSlotPolicy.MISSED_CALL_ID_SALT
            )
        }
    }

    @Test
    fun chatActionOffsetsHoldForEmptyChatId() {
        assertEquals(0, NotificationSlotPolicy.chatRequestCode(""))
        assertEquals(1, NotificationSlotPolicy.markReadRequestCode(""))
        assertEquals(2, NotificationSlotPolicy.quickReplyRequestCode(""))
        assertEquals("maodouchat-notify://chat/", NotificationSlotPolicy.chatDataUri(""))
    }

    @Test
    fun reminderAndChatNamespacesStaySeparateForEmptyId() {
        assertEquals("maodouchat_reminder_", NotificationSlotPolicy.reminderTag(""))
        assertEquals("maodouchat_", NotificationSlotPolicy.messageTag(""))
        assertEquals("reminder_".hashCode(), NotificationSlotPolicy.reminderRequestCode(""))
        assertEquals(0, NotificationSlotPolicy.chatRequestCode(""))
        assertNotEquals(
            NotificationSlotPolicy.reminderRequestCode(""),
            NotificationSlotPolicy.chatRequestCode("")
        )
    }

    @Test
    fun scheduledFailedIdEqualsItsFormulaAndDiffersFromFixedMessageId() {
        assertEquals(
            "scheduled_message_failed".hashCode(),
            NotificationSlotPolicy.scheduledMessageFailedNotifyId()
        )
        assertNotEquals(
            NotificationSlotPolicy.messageNotifyId(),
            NotificationSlotPolicy.scheduledMessageFailedNotifyId()
        )
    }
}
