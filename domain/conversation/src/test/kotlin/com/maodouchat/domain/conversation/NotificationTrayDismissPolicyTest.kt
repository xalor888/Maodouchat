package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTrayDismissPolicyTest {

    private fun item(
        id: String = "n1",
        type: String = "MESSAGE",
        mergeKey: String = "msg_c1",
        deeplink: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = NotificationCenterItem(
        id = id,
        type = type,
        mergeKey = mergeKey,
        title = "t",
        deeplink = deeplink,
        extra = extra,
    )

    @Test
    fun `resolveLiveItem prefers id match`() {
        val items = listOf(item(id = "old"), item(id = "n1", mergeKey = "other"))
        assertEquals("n1", NotificationTrayDismissPolicy.resolveLiveItem(items, "n1")?.id)
    }

    @Test
    fun `resolveLiveItem falls back to type plus mergeKey`() {
        val items = listOf(item(id = "head", type = "MESSAGE", mergeKey = "msg_c1"))
        val hint = item(id = "stale", type = "MESSAGE", mergeKey = "msg_c1")
        assertEquals("head", NotificationTrayDismissPolicy.resolveLiveItem(items, "stale", hint)?.id)
    }

    @Test
    fun `resolveLiveItem returns null without id or hint match`() {
        val items = listOf(item(id = "n1"))
        assertNull(NotificationTrayDismissPolicy.resolveLiveItem(items, "nope", item(id = "x", type = "MESSAGE", mergeKey = "msg_c9")))
    }

    @Test
    fun `resolveLiveItem ignores hint without mergeKey`() {
        val items = listOf(item(id = "n1", mergeKey = "msg_c1"))
        val hint = item(id = "x", type = "MESSAGE", mergeKey = "")
        assertNull(NotificationTrayDismissPolicy.resolveLiveItem(items, "nope", hint))
    }

    @Test
    fun `dismissActionFor missed call needs callId`() {
        assertEquals(
            NotificationTrayDismissAction.CancelMissedCall("call-1"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "MISSED_CALL", mergeKey = "missed_call-1", extra = mapOf("callId" to "call-1"))
            )
        )
        assertNull(
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "MISSED_CALL", mergeKey = "missed_call-1")
            )
        )
    }

    @Test
    fun `dismissActionFor ai task prefers chat over task`() {
        assertEquals(
            NotificationTrayDismissAction.CancelAiTaskRemindersForChat("c1"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "AI_TASK", mergeKey = "ai_tasks_c1", extra = mapOf("chatId" to "c1", "taskId" to "t1"))
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAiTaskReminder("t1"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "AI_TASK", mergeKey = "ai_task_t1", extra = mapOf("taskId" to "t1"))
            )
        )
        assertNull(
            NotificationTrayDismissPolicy.dismissActionFor(item(type = "AI_TASK", mergeKey = "ai_task_x"))
        )
    }

    @Test
    fun `dismissActionFor message resolves chatId from extra deeplink then mergeKey`() {
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c1"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(extra = mapOf("chatId" to "c1"))
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c9"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(deeplink = "maodouchat:chat:c9", mergeKey = "msg_c9")
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c9"),
            NotificationTrayDismissPolicy.dismissActionFor(item(mergeKey = "msg_c9"))
        )
        assertNull(NotificationTrayDismissPolicy.dismissActionFor(item(mergeKey = "msg_")))
    }

    @Test
    fun `dismissActionFor post resolves postId from extra deeplink then mergeKey`() {
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p1"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "POST_INTERACTION", mergeKey = "post_p1", extra = mapOf("postId" to "p1"))
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p2"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "POST_INTERACTION", mergeKey = "post_p2", deeplink = "maodouchat:post:p2")
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p3"),
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "POST_INTERACTION", mergeKey = "post_p3")
            )
        )
    }

    @Test
    fun `dismissActionFor friend request via type or contacts deeplink`() {
        assertEquals(
            NotificationTrayDismissAction.CancelAllFriendRequests,
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "FRIEND_REQUEST", mergeKey = "friend_request")
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAllFriendRequests,
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:contacts")
            )
        )
    }

    @Test
    fun `dismissActionFor group invite via type or invites deeplink`() {
        assertEquals(
            NotificationTrayDismissAction.CancelAllGroupInvites,
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "GROUP_INVITE", mergeKey = "group_invite")
            )
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAllGroupInvites,
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:group_invites")
            )
        )
    }

    @Test
    fun `dismissActionFor unknown type returns null`() {
        assertNull(
            NotificationTrayDismissPolicy.dismissActionFor(
                item(type = "SECURITY", mergeKey = "sec_1")
            )
        )
    }

    @Test
    fun `affectedItemsForChat keeps message and ai-task rows of the chat`() {
        val items = listOf(
            item(id = "a", mergeKey = "msg_c1"),
            item(id = "b", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item(id = "c", mergeKey = "msg_c2"),
            item(id = "d", type = "REPORT", mergeKey = "report_1"),
        )
        assertEquals(
            listOf("a", "b"),
            NotificationTrayDismissPolicy.affectedItemsForChat(items, "c1").map { it.id }
        )
    }
}
