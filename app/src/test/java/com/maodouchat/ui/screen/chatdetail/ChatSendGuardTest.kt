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
 * G66 安全网：`ChatSendGuard` 的发送准入与重试准入契约。
 *
 * 这些守卫此前散在 `ChatDetailViewModel.sendMessage()`（90 行）与 `retrySendMessage()`（68 行）里，
 * **一个用例都没有**。而它们每一条都对应一种真实的用户可见故障：
 * - 空文本/超长不拦 → 发出空气泡、或服务端按超长正文拒绝；
 * - 未登录/被屏蔽不拦 → 请求白跑一圈再失败，用户看到的是「转圈后报错」而不是即时提示；
 * - 群 @所有人 不校验角色 → 普通成员能 @所有人（骚扰面）；
 * - 重试不看归属与状态 → 能把**别人**的失败消息或已发送消息重新发一遍。
 */
class ChatSendGuardTest {

    private val me = User(id = "me", name = "Me")

    private fun state(
        inputText: String = "",
        isSending: Boolean = false,
        isContactBlocked: Boolean = false,
        chatIsGroup: Boolean = false,
        myMemberRole: String? = null,
        silentSend: Boolean = false,
        participants: List<User> = listOf(me, User(id = "u2", name = "U2")),
        messages: List<Message> = emptyList(),
    ) = ChatDetailUiState(
        inputText = inputText,
        isSending = isSending,
        isContactBlocked = isContactBlocked,
        chatIsGroup = chatIsGroup,
        myMemberRole = myMemberRole,
        silentSend = silentSend,
        chat = com.maodouchat.data.model.Chat(
            id = "c1",
            participants = participants,
            isGroup = chatIsGroup,
        ),
        messages = messages,
    )

    private fun msg(
        id: String,
        senderId: String = "me",
        status: MessageStatus = MessageStatus.FAILED,
        type: MessageType = MessageType.TEXT,
        content: String = "hello",
    ) = Message(id = id, chatId = "c1", senderId = senderId, content = content, type = type, status = status, timestamp = 1L)

    // ─── 发送准入 ───

    @Test
    fun `blank text is rejected before anything is built`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "   "),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Reject>(result)
        assertEquals(ChatSendGuard.SendRejectReason.BLANK, result.reason)
    }

    @Test
    fun `oversized text is clipped rather than rejected`() {
        val long = "x".repeat(ChatSendGuard.MAX_TEXT_LENGTH + 500)
        val result = ChatSendGuard.checkSend(
            state = state(inputText = long),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result)
        assertEquals(ChatSendGuard.MAX_TEXT_LENGTH, result.text.length, "超长文本必须被截断到上限")
    }

    @Test
    fun `force text is used verbatim and never touches the composer`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "用户草稿"),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = "定时消息立即发",
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result)
        assertEquals("定时消息立即发", result.text)
        assertFalse(result.clearDraft, "forceText 发送不得清用户草稿")
    }

    @Test
    fun `a missing session is rejected with a diagnosable reason`() {
        val noToken = ChatSendGuard.checkSend(state(), ownerUserId = "me", token = "", silent = null, forceText = "hi", mentionsEnabled = true)
        assertIs<ChatSendGuard.SendDecision.Reject>(noToken)
        assertEquals(ChatSendGuard.SendRejectReason.NO_SESSION, noToken.reason)

        val noOwner = ChatSendGuard.checkSend(state(), ownerUserId = "", token = "tok", silent = null, forceText = "hi", mentionsEnabled = true)
        assertEquals(ChatSendGuard.SendRejectReason.NO_SESSION, (noOwner as ChatSendGuard.SendDecision.Reject).reason)
    }

    @Test
    fun `a blocked contact is rejected`() {
        val result = ChatSendGuard.checkSend(
            state = state(isContactBlocked = true),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = "hi",
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Reject>(result)
        assertEquals(ChatSendGuard.SendRejectReason.BLOCKED, result.reason)
    }

    @Test
    fun `an in-flight send blocks reentry so double taps cannot duplicate bubbles`() {
        val result = ChatSendGuard.checkSend(
            state = state(isSending = true, inputText = "hi"),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Reject>(result)
        assertEquals(ChatSendGuard.SendRejectReason.ALREADY_SENDING, result.reason)
    }

    // ─── 群 @所有人 ───

    @Test
    fun `mentioning everyone in a group is allowed for owner and admin`() {
        listOf("OWNER", "ADMIN").forEach { role ->
            val result = ChatSendGuard.checkSend(
                state = state(inputText = "@所有人 看这里", chatIsGroup = true, myMemberRole = role),
                ownerUserId = "me",
                token = "tok",
                silent = null,
                forceText = null,
                mentionsEnabled = true,
            )
            assertIs<ChatSendGuard.SendDecision.Allow>(result, "$role 应当可以 @所有人")
        }
    }

    @Test
    fun `mentioning everyone in a group is rejected for a plain member`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "@所有人 看这里", chatIsGroup = true, myMemberRole = "MEMBER"),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Reject>(result)
        assertEquals(ChatSendGuard.SendRejectReason.MENTION_EVERYONE_FORBIDDEN, result.reason)
    }

    @Test
    fun `an unloaded group role fails open so a fresh admin is not wrongly blocked`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "@所有人 看这里", chatIsGroup = true, myMemberRole = null),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result, "角色未加载时必须 fail-open，否则刚进群的管理员被误拦")
    }

    @Test
    fun `mentions are ignored entirely when the feature flag is off`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "@所有人 看这里", chatIsGroup = true, myMemberRole = "MEMBER"),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = false,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result, "功能关着时不该有 @ 权限判定")
        assertTrue(result.meta.mentions.isEmpty(), "功能关着时不得提取任何 mention")
    }

    @Test
    fun `mentions are never extracted outside a group`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "@u2 看这里", chatIsGroup = false),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result)
        assertTrue(result.meta.mentions.isEmpty(), "直聊没有 @所有人 语义")
    }

    // ─── meta 组装 ───

    @Test
    fun `meta carries mentions reply target markdown and silent`() {
        val reply = msg("m0", senderId = "u2", status = MessageStatus.SENT)
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "# 标题", chatIsGroup = true, myMemberRole = "OWNER", silentSend = true),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
            replyTarget = reply,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result)
        assertEquals("m0", result.meta.replyToId)
        assertTrue(result.meta.markdown, "看起来是 markdown 必须标记")
        assertTrue(result.meta.silent, "silentSend 必须带进 meta")
    }

    @Test
    fun `silent is consumed once so it does not leak into the next message`() {
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "hi", silentSend = true),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result)
        assertTrue(result.consumeSilent, "用了 silentSend 就必须一次性消费掉")
    }

    @Test
    fun `an explicit silent argument overrides the sticky flag`() {
        val explicitOff = ChatSendGuard.checkSend(
            state = state(inputText = "hi", silentSend = true),
            ownerUserId = "me",
            token = "tok",
            silent = false,
            forceText = null,
            mentionsEnabled = true,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(explicitOff)
        assertFalse(explicitOff.meta.silent, "显式 false 必须覆盖粘性开关")
        assertFalse(explicitOff.consumeSilent, "没有消费粘性开关")
    }

    @Test
    fun `a forced meta wins over the derived one`() {
        val forced = MessageMeta(mentions = listOf("u9"), replyToId = "r1", silent = true)
        val result = ChatSendGuard.checkSend(
            state = state(inputText = "hi", chatIsGroup = true, myMemberRole = "MEMBER"),
            ownerUserId = "me",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
            forcedMeta = forced,
        )
        assertIs<ChatSendGuard.SendDecision.Allow>(result)
        assertEquals(forced, result.meta, "forcedMeta 必须原样采用")
    }

    // ─── 重试准入 ───

    @Test
    fun `retry only accepts my own failed message`() {
        val mine = msg("m1", senderId = "me", status = MessageStatus.FAILED)
        val ok = ChatSendGuard.checkRetry(state(messages = listOf(mine)), messageId = "m1", ownerUserId = "me", token = "tok")
        assertIs<ChatSendGuard.RetryDecision.Allow>(ok)
        assertEquals(mine, ok.message)

        // 别人的失败消息
        val theirs = msg("m2", senderId = "u2", status = MessageStatus.FAILED)
        val notMine = ChatSendGuard.checkRetry(state(messages = listOf(theirs)), messageId = "m2", ownerUserId = "me", token = "tok")
        assertIs<ChatSendGuard.RetryDecision.Reject>(notMine)
        assertEquals(ChatSendGuard.RetryRejectReason.NOT_MY_MESSAGE, notMine.reason)

        // 已发送的消息不得重试
        val sent = msg("m3", senderId = "me", status = MessageStatus.SENT)
        val notFailed = ChatSendGuard.checkRetry(state(messages = listOf(sent)), messageId = "m3", ownerUserId = "me", token = "tok")
        assertEquals(
            ChatSendGuard.RetryRejectReason.NOT_FAILED,
            (notFailed as ChatSendGuard.RetryDecision.Reject).reason,
        )

        // 不存在的消息
        val missing = ChatSendGuard.checkRetry(state(), messageId = "nope", ownerUserId = "me", token = "tok")
        assertEquals(ChatSendGuard.RetryRejectReason.NOT_FOUND, (missing as ChatSendGuard.RetryDecision.Reject).reason)
    }

    @Test
    fun `retry routes attachment-shaped failures to the attachment path`() {
        val image = msg("m4", type = MessageType.IMAGE, status = MessageStatus.FAILED)
        val result = ChatSendGuard.checkRetry(state(messages = listOf(image)), messageId = "m4", ownerUserId = "me", token = "tok")

        assertIs<ChatSendGuard.RetryDecision.NeedsAttachmentRetry>(result)
        assertEquals(image, result.message)
    }

    @Test
    fun `retry rejects a missing session`() {
        val mine = msg("m5")
        val result = ChatSendGuard.checkRetry(state(messages = listOf(mine)), messageId = "m5", ownerUserId = "me", token = "")
        assertIs<ChatSendGuard.RetryDecision.Reject>(result)
        assertEquals(ChatSendGuard.RetryRejectReason.NO_SESSION, result.reason)
    }

    @Test
    fun `retry rejects an unsupported message type instead of crashing`() {
        val weird = msg("m6", type = MessageType.SK_DIST, status = MessageStatus.FAILED)
        val result = ChatSendGuard.checkRetry(state(messages = listOf(weird)), messageId = "m6", ownerUserId = "me", token = "tok")
        assertIs<ChatSendGuard.RetryDecision.Reject>(result)
        assertEquals(ChatSendGuard.RetryRejectReason.UNSUPPORTED_TYPE, result.reason)
    }

    @Test
    fun `retry markdown and text both take the text path`() {
        listOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION, MessageType.NUDGE)
            .forEach { type ->
                val m = msg("m-${type.name}", type = type)
                val result = ChatSendGuard.checkRetry(state(messages = listOf(m)), messageId = m.id, ownerUserId = "me", token = "tok")
                assertIs<ChatSendGuard.RetryDecision.Allow>(result, "$type 应当走文本重试路径")
            }
    }

    @Test
    fun `the allowed decision carries everything the intent factory needs`() {
        val decision = ChatSendGuard.checkSend(
            state = state(inputText = "hi"),
            ownerUserId = "u1",
            token = "tok",
            silent = null,
            forceText = null,
            mentionsEnabled = true,
        ) as ChatSendGuard.SendDecision.Allow

        // 乐观气泡的构造已归 ChatSendIntentFactory（G68），守卫只负责给出「要发什么」。
        // 这里断言 Allow 决策自含全部必要字段，工厂不需要再猜。
        assertEquals("hi", decision.text)
        assertEquals(MessageType.TEXT, decision.messageType)
        assertTrue(decision.clearDraft, "普通发送必须清草稿")
        val optimistic = ChatSendIntentFactory.build(
            chatId = "c1",
            senderId = "u1",
            messageId = "m_test",
            timestamp = 42L,
            content = decision.text,
            type = decision.messageType,
            meta = decision.meta,
        )
        assertEquals("m_test", optimistic.id)
        assertEquals(MessageStatus.SENDING, optimistic.status)
        assertEquals("c1", optimistic.chatId)
        assertEquals("u1", optimistic.senderId)
        assertNull(optimistic.meta.forwardedFrom, "普通发送不得带转发来源")
    }
}
