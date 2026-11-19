package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class NotificationCenterTypeTest {

    @Test
    fun `all types are distinct non-blank constants`() {
        val all = listOf(
            NotificationCenterType.MESSAGE,
            NotificationCenterType.MISSED_CALL,
            NotificationCenterType.AI_TASK,
            NotificationCenterType.POST_INTERACTION,
            NotificationCenterType.GROUP_INVITE,
            NotificationCenterType.SECURITY,
            NotificationCenterType.REPORT,
            NotificationCenterType.MODERATION,
            NotificationCenterType.FRIEND_REQUEST,
        )
        assertEquals(9, all.toSet().size)
        assertTrue(all.all { it.isNotBlank() })
    }
}
