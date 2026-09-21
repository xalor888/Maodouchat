package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G68 安全网：`ChatSendIntentFactory` 的守卫与「待发意图」构造契约。
 *
 * 这两块此前分散在 `sendMessage()` 与 `sendNudge()` 里、各写一遍且**零覆盖**：
 * - nudge 四条守卫（密聊禁用 / 功能开关 / 空 chatId / 未登录含占位 owner）漏一条，
 *   用户就会看到「点了没反应」或者一个永远 SENDING 的僵尸气泡；
 * - 待发意图的 `chatId` 取错（没用 `activeChatId` 回落 `chatId`）→ 消息落到别的会话里；
 * - `status` 不是 `SENDING` → 重试逻辑认不出它，永远无法重发。
 */
class ChatSendIntentFactoryTest {

    private fun state(
        isSecretChat: Boolean = false,
        contactName: String = "U2",
        messages: List<Message> = emptyList(),
    ) = ChatDetailUiState(
        isSecretChat = isSecretChat,
        contact = User(id = "u2", name = contactName),
        chat = com.maodouchat.data.model.Chat(id = "c1", participants = emptyList()),
        messages = messages,
    )

    // ─── nudge 守卫 ───

    @Test
    fun `nudge is refused in a secret chat`() {
        val result = ChatSendIntentFactory.checkNudge(
            state = state(isSecretChat = true),
            activeChatId = "c1",
            ownerUserId = "u1",
            token = "tok",
            nudgeEnabled = true,
        )
        assertIs<ChatSendIntentFactory.NudgeDecision.Reject>(result)
        assertEquals(ChatSendIntentFactory.NudgeRejectReason.SECRET_CHAT, result.reason)
    }

    @Test
    fun `nudge is refused when the feature is off`() {
        val result = ChatSendIntentFactory.checkNudge(
            state = state(),
            activeChatId = "c1",
            ownerUserId = "u1",
            token = "tok",
            nudgeEnabled = false,
        )
        assertIs<ChatSendIntentFactory.NudgeDecision.Reject>(result)
        assertEquals(ChatSendIntentFactory.NudgeRejectReason.DISABLED, result.reason)
    }

    @Test
    fun `nudge is refused without a chat id`() {
        val result = ChatSendIntentFactory.checkNudge(
            state = state(),
            activeChatId = "   ",
            ownerUserId = "u1",
            token = "tok",
            nudgeEnabled = true,
        )
        assertIs<ChatSendIntentFactory.NudgeDecision.Reject>(result)
        assertEquals(ChatSendIntentFactory.NudgeRejectReason.NO_CHAT, result.reason)
    }

    @Test
    fun `nudge is refused without a real session`() {
        listOf(
            Triple("u1", "", "空 token"),
            Triple("", "tok", "空 owner"),
            Triple(ChatSendIntentFactory.PLACEHOLDER_OWNER, "tok", "占位 owner"),
        ).forEach { (owner, token, label) ->
            val result = ChatSendIntentFactory.checkNudge(
                state = state(),
                activeChatId = "c1",
                ownerUserId = owner,
                token = token,
                nudgeEnabled = true,
            )
            assertIs<ChatSendIntentFactory.NudgeDecision.Reject>(result, "$label 必须被拒")
            assertEquals(ChatSendIntentFactory.NudgeRejectReason.NO_SESSION, result.reason, label)
        }
    }

    @Test
    fun `nudge is allowed once every guard passes`() {
        val result = ChatSendIntentFactory.checkNudge(
            state = state(),
            activeChatId = "c1",
            ownerUserId = "u1",
            token = "tok",
            nudgeEnabled = true,
        )
        assertIs<ChatSendIntentFactory.NudgeDecision.Allow>(result)
        assertEquals("u1", result.ownerUserId)
        assertEquals("c1", result.chatId)
    }

    // ─── 待发意图 ───

    @Test
    fun `the intent carries every field the outbox needs`() {
        val intent = ChatSendIntentFactory.build(
            chatId = "c1",
            senderId = "u1",
            messageId = "m_fixed",
            timestamp = 7L,
            content = "hello",
            type = MessageType.TEXT,
            meta = MessageMeta(mentions = listOf("u2"), replyToId = "r1"),
        )

        assertEquals("m_fixed", intent.id)
        assertEquals("c1", intent.chatId)
        assertEquals("u1", intent.senderId)
        assertEquals(7L, intent.timestamp)
        assertEquals("hello", intent.content)
        assertEquals(MessageType.TEXT, intent.type)
        assertEquals(MessageStatus.SENDING, intent.status, "待发意图必须从 SENDING 起，否则重试认不出它")
        assertEquals(listOf("u2"), intent.meta.mentions)
        assertEquals("r1", intent.meta.replyToId)
    }

    @Test
    fun `an intent without meta still starts SENDING`() {
        val intent = ChatSendIntentFactory.build(
            chatId = "c1",
            senderId = "u1",
            messageId = "m_x",
            timestamp = 1L,
            content = "hi",
            type = MessageType.NUDGE,
            meta = null,
        )
        assertEquals(MessageStatus.SENDING, intent.status)
        assertTrue(intent.meta.mentions.isEmpty(), "没给 meta 时不该凭空长出 mention")
    }

    @Test
    fun `a fresh message id is unique per call`() {
        val a = ChatSendIntentFactory.newMessageId()
        val b = ChatSendIntentFactory.newMessageId()
        assertTrue(a.startsWith("m_"), "消息 id 必须带 m_ 前缀：$a")
        assertFalse(a == b, "连续两次生成的 id 不能相同")
    }

    // ─── chatId 回落 ───

    @Test
    fun `an active chat id wins over the constructor chat id`() {
        assertEquals("active", ChatSendIntentFactory.effectiveChatId(activeChatId = "active", fallbackChatId = "ctor"))
    }

    @Test
    fun `a blank active chat id falls back to the constructor chat id`() {
        assertEquals("ctor", ChatSendIntentFactory.effectiveChatId(activeChatId = "   ", fallbackChatId = "ctor"))
        assertEquals("ctor", ChatSendIntentFactory.effectiveChatId(activeChatId = "", fallbackChatId = "ctor"))
    }

    @Test
    fun `both blank yields blank so callers can refuse`() {
        assertEquals("", ChatSendIntentFactory.effectiveChatId(activeChatId = "", fallbackChatId = ""))
    }

    // ─── nudge 意图 ───

    @Test
    fun `a nudge intent is typed NUDGE and carries the localized body`() {
        val intent = ChatSendIntentFactory.build(
            chatId = "c1",
            senderId = "u1",
            messageId = "m_n",
            timestamp = 3L,
            content = "你戳了戳 U2",
            type = MessageType.NUDGE,
            meta = null,
        )
        assertEquals(MessageType.NUDGE, intent.type)
        assertEquals("你戳了戳 U2", intent.content)
        assertEquals(MessageStatus.SENDING, intent.status)
        assertNull(intent.meta.forwardedFrom, "nudge 不得带转发来源")
    }

    @Test
    fun `a blank contact name is resolved by the caller not the factory`() {
        // 工厂不碰资源字符串：占位文案由调用方决定，这里只验证「空名也能造出意图」
        val intent = ChatSendIntentFactory.build(
            chatId = "c1",
            senderId = "u1",
            messageId = "m_b",
            timestamp = 1L,
            content = "你戳了戳 对方",
            type = MessageType.NUDGE,
            meta = null,
        )
        assertTrue(intent.content.isNotBlank(), "调用方负责把空名换成占位文案")
    }
}
