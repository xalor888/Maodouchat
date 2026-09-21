package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G70 安全网：`ChatHistoryLoadPolicy` 的「历史页收尾」判定契约。
 *
 * 这段判定此前内联在 `ChatDetailViewModel.loadChat()` 的 `withContext(Dispatchers.IO)` 里，
 * **一个用例都没有**。它决定三件用户可见的事：
 * - 未读分隔线标在哪（标错 → 用户以为看全了，实际漏看）；
 * - 密聊**绝不**发已读回执（发了 → 对方知道你在看，密聊的隐私承诺破功）；
 * - 回执带不带 groupRevision（不带 → 服务端按错误 epoch 记账）。
 */
class ChatHistoryLoadPolicyTest {

    private fun msg(id: String, timestamp: Long, type: MessageType = MessageType.TEXT) =
        Message(id = id, chatId = "c1", senderId = "u2", content = "", type = type, timestamp = timestamp)

    private fun plan(
        messages: List<Message>,
        unreadCount: Int,
        isSecretChat: Boolean,
        readBoundaryMessageId: String?,
        groupRevision: Long?,
    ) = ChatHistoryLoadPolicy.planHistoryLoad(
        messages = messages,
        unreadCount = unreadCount,
        isSecretChat = isSecretChat,
        readBoundaryMessageId = readBoundaryMessageId,
        groupRevision = groupRevision,
    )

    private val fourMessages = (1..4).map { msg("m$it", it.toLong() * 10) }

    // ─── 未读分隔线 ───

    @Test
    fun `the unread separator marks the oldest unread message`() {
        val result = plan(fourMessages, unreadCount = 2, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = null)

        assertEquals("m3", result.unreadSeparatorId, "未读 2 条时分隔线应落在倒数第 2 条")
    }

    @Test
    fun `no unread means no separator`() {
        val result = plan(fourMessages, unreadCount = 0, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = null)

        assertNull(result.unreadSeparatorId, "没有未读就不该有分隔线")
    }

    // ─── 密聊：绝不发回执 ───

    @Test
    fun `a secret chat arms disappearing instead of sending a receipt`() {
        val result = plan(
            messages = fourMessages,
            unreadCount = 2,
            isSecretChat = true,
            readBoundaryMessageId = "m4",
            groupRevision = null,
        )

        assertTrue(result.armSecretDisappearing, "密聊必须走阅后即焚")
        assertFalse(result.enqueueReadReceipt, "密聊绝不发已读回执——发了对方就知道你在看")
        assertNull(result.readReceiptThroughMessageId, "密聊不该产出回执参数")
    }

    @Test
    fun `a secret chat with nothing unread still arms disappearing`() {
        // 密聊即使没有未读也要武装阅后即焚：进入会话本身就要起定时器
        val result = plan(
            messages = fourMessages,
            unreadCount = 0,
            isSecretChat = true,
            readBoundaryMessageId = null,
            groupRevision = null,
        )

        assertTrue(result.armSecretDisappearing, "密聊进会话就要武装阅后即焚，与有无未读无关")
        assertFalse(result.enqueueReadReceipt)
    }

    // ─── 非密聊：有未读且有边界才发 ───

    @Test
    fun `a normal chat with unread enqueues a receipt through the boundary`() {
        val result = plan(
            messages = fourMessages,
            unreadCount = 2,
            isSecretChat = false,
            readBoundaryMessageId = "m4",
            groupRevision = null,
        )

        assertFalse(result.armSecretDisappearing, "非密聊不该武装阅后即焚")
        assertTrue(result.enqueueReadReceipt, "有未读就该发回执")
        assertEquals("m4", result.readReceiptThroughMessageId, "回执必须带到读边界")
    }

    @Test
    fun `no unread means no receipt even in a normal chat`() {
        val result = plan(fourMessages, unreadCount = 0, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = null)

        assertFalse(result.enqueueReadReceipt, "没有未读不该发回执（空转请求）")
        assertNull(result.readReceiptThroughMessageId)
    }

    @Test
    fun `no read boundary means no receipt`() {
        // 会话里没有任何来电消息时拿不到边界 → 不能拿 null 去发回执
        val result = plan(fourMessages, unreadCount = 3, isSecretChat = false, readBoundaryMessageId = null, groupRevision = null)

        assertFalse(result.enqueueReadReceipt, "没有读边界时不得发回执")
        assertNull(result.readReceiptThroughMessageId)
    }

    // ─── groupRevision 取值规则 ───

    @Test
    fun `a group revision is carried only when it is a group chat`() {
        val inGroup = plan(fourMessages, unreadCount = 1, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = 7L)
        assertEquals(7L, inGroup.readReceiptGroupRevision, "群会话必须带 revision")

        val direct = plan(fourMessages, unreadCount = 1, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = null)
        assertNull(direct.readReceiptGroupRevision, "直聊不该带 revision")
    }

    @Test
    fun `a group chat without a revision still enqueues with a null revision`() {
        // 群但 revision 缺失（刚建群/脏数据）：仍然要发回执，只是 revision 为 null
        val result = plan(fourMessages, unreadCount = 1, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = null)

        assertTrue(result.enqueueReadReceipt, "群但缺 revision 不该阻断回执")
        assertNull(result.readReceiptGroupRevision)
    }

    // ─── 确定性 ───

    @Test
    fun `the same inputs always produce the same plan`() {
        val a = plan(fourMessages, unreadCount = 2, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = 3L)
        val b = plan(fourMessages, unreadCount = 2, isSecretChat = false, readBoundaryMessageId = "m4", groupRevision = 3L)

        assertEquals(a, b, "纯函数：同输入必须同输出（否则水印/回执会随调用抖动）")
    }

    @Test
    fun `control messages never become the separator`() {
        val withSk = listOf(
            msg("m1", 10L),
            msg("sk1", 20L, MessageType.SK_DIST),
            msg("m2", 30L),
        )
        val result = plan(withSk, unreadCount = 1, isSecretChat = false, readBoundaryMessageId = "m2", groupRevision = null)

        assertEquals("m2", result.unreadSeparatorId, "SK_DIST 不参与未读分母")
    }
}
