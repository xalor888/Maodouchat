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

    @Test
    fun `purge flag dominates regardless of id match`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u2", true))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", null, true))
    }

    @Test
    fun `blank current user never matches even with a valid owner`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "   ", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "", false))
    }

    @Test
    fun `resolveOpenChatId trims tabs and newlines`() {
        assertEquals("chat-1", NotificationIntentPolicy.resolveOpenChatId("\t chat-1 \n"))
        assertNull(NotificationIntentPolicy.resolveOpenChatId("\t \n"))
    }

    @Test
    fun `resolveOpenChatId keeps inner tabs`() {
        assertEquals("chat\t1", NotificationIntentPolicy.resolveOpenChatId("chat\t1"))
    }

    @Test
    fun `resolveOpenChatId preserves case`() {
        assertEquals("CHAT_1", NotificationIntentPolicy.resolveOpenChatId("  CHAT_1 "))
    }

    @Test
    fun `ids compare verbatim without trimming`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount(" u1 ", "u1", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", " u1 ", false))
        assertTrue(NotificationIntentPolicy.belongsToCurrentAccount("u1 ", "u1 ", false))
    }

    @Test
    fun `case mismatch in either direction refuses`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "U1", false))
        assertTrue(NotificationIntentPolicy.belongsToCurrentAccount("U1", "U1", false))
    }

    @Test
    fun `nbsp-only chat id is dropped by trim`() {
        // Kotlin 的 trim 按 Char.isWhitespace 判定，NBSP 也算空白会被剥掉。
        assertNull(NotificationIntentPolicy.resolveOpenChatId("\u00A0"))
        assertEquals("chat-1", NotificationIntentPolicy.resolveOpenChatId("\u00A0chat-1\u00A0"))
    }

    @Test
    fun `nbsp owner ids are blank and match nobody`() {
        // 同理 isBlank 把 NBSP 判空白，纯 NBSP 的 owner 属于任何人都不成立。
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("\u00A0", "\u00A0", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("\u00A0", "u1", false))
    }

    @Test
    fun `single character and numeric chat ids pass through`() {
        assertEquals("x", NotificationIntentPolicy.resolveOpenChatId("x"))
        assertEquals("42", NotificationIntentPolicy.resolveOpenChatId(" 42 "))
        assertEquals("0", NotificationIntentPolicy.resolveOpenChatId("0"))
    }
}
