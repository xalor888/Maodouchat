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

    @Test
    fun `purge in progress drops the intent even for the current account`() {
        // 会话清理中：owner 对得上也不消费，防止旧账号的通知点开新账号的会话。
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", true))
    }

    @Test
    fun `blank owner never belongs to anyone`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("   ", "   ", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("", "u1", false))
    }

    @Test
    fun `account match is case sensitive`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("U1", "u1", false))
    }

    @Test
    fun `resolveOpenChatId keeps inner whitespace`() {
        assertEquals("chat 1", NotificationIntentPolicy.resolveOpenChatId("  chat 1  "))
    }

    @Test
    fun `resolveOpenChatId is stable on repeat`() {
        assertEquals(
            NotificationIntentPolicy.resolveOpenChatId("chat-1"),
            NotificationIntentPolicy.resolveOpenChatId("chat-1"),
        )
        assertNull(NotificationIntentPolicy.resolveOpenChatId("   "))
    }
}
