package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationIntentPolicyTest {
    @Test
    fun onlyCurrentAccountCanConsumeNotificationIntent() {
        assertTrue(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u2", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount(null, "u1", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", null, false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", true))
    }

    @Test
    fun resolveOpenChatIdDropsBlank() {
        assertNull(NotificationIntentPolicy.resolveOpenChatId(null))
        assertNull(NotificationIntentPolicy.resolveOpenChatId(""))
        assertNull(NotificationIntentPolicy.resolveOpenChatId("   "))
        assertEquals("chat-1", NotificationIntentPolicy.resolveOpenChatId(" chat-1 "))
    }
}
