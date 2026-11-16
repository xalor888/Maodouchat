package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterType
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIconKindTest {

    @Test
    fun `each non interaction type maps to its kind`() {
        assertEquals(NotificationIconKind.MESSAGE, notificationIconKind(NotificationCenterType.MESSAGE, ""))
        assertEquals(NotificationIconKind.MISSED_CALL, notificationIconKind(NotificationCenterType.MISSED_CALL, ""))
        assertEquals(NotificationIconKind.AI_TASK, notificationIconKind(NotificationCenterType.AI_TASK, ""))
        assertEquals(NotificationIconKind.GROUP_INVITE, notificationIconKind(NotificationCenterType.GROUP_INVITE, ""))
        assertEquals(NotificationIconKind.SECURITY, notificationIconKind(NotificationCenterType.SECURITY, ""))
        assertEquals(NotificationIconKind.FRIEND_REQUEST, notificationIconKind(NotificationCenterType.FRIEND_REQUEST, ""))
    }

    @Test
    fun `post interaction comment kinds map to comment`() {
        assertEquals(NotificationIconKind.COMMENT, notificationIconKind(NotificationCenterType.POST_INTERACTION, "comment"))
        assertEquals(NotificationIconKind.COMMENT, notificationIconKind(NotificationCenterType.POST_INTERACTION, "comment_like"))
    }

    @Test
    fun `post interaction other kinds map to like`() {
        listOf("like", "post_like", "", "  ", "COMMENT", "Comment").forEach { kind ->
            assertEquals("kind=$kind", NotificationIconKind.LIKE, notificationIconKind(NotificationCenterType.POST_INTERACTION, kind))
        }
    }

    @Test
    fun `report moderation and unknown types fall back to default`() {
        listOf(NotificationCenterType.REPORT, NotificationCenterType.MODERATION, "SOMETHING_NEW", "").forEach { type ->
            assertEquals("$type", NotificationIconKind.DEFAULT, notificationIconKind(type, ""))
        }
    }
}
