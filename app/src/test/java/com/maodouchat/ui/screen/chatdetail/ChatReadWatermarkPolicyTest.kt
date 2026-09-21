package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G67 安全网：`ChatReadWatermarkPolicy` 的「新未读」与水印判定契约。
 *
 * 这些判定此前全部内联在 `ChatDetailViewModel.observeMessageStatus()`（83 行）里，**零覆盖**。
 * 而它们直接决定已读回执的正确性——判错的后果都不是崩溃，而是**静默的数据不一致**：
 * - 把自己的消息也算进未读 → 给自己发回执，服务端记一条无意义的已读；
 * - `SK_DIST` 计入未读 → 密钥分发被误标已读，后续群消息解不开；
 * - seen-set 不幂等 → 同一条消息每次状态变化都重发回执（刷屏 + 服务端压力）；
 * - 水印选错 → 「已读到哪」比实际少，对方永远显示未读。
 */
class ChatReadWatermarkPolicyTest {

    private val me = "u1"

    private fun msg(
        id: String,
        senderId: String = "u2",
        timestamp: Long = 1L,
        type: MessageType = MessageType.TEXT,
        status: MessageStatus = MessageStatus.SENT,
    ) = Message(id = id, chatId = "c1", senderId = senderId, content = "", type = type, timestamp = timestamp, status = status)

    private fun input(
        messages: List<Message>,
        hasChat: Boolean = true,
        isActiveChat: Boolean = true,
        ownerUserId: String = me,
        sessionMayContinue: Boolean = true,
    ) = ChatReadWatermarkPolicy.Input(
        messages = messages,
        hasChat = hasChat,
        isActiveChat = isActiveChat,
        ownerUserId = ownerUserId,
        sessionMayContinue = sessionMayContinue,
    )

    // ─── 整体跳过 ───

    @Test
    fun `no chat means nothing to mark`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(input(messages = listOf(msg("m1")), hasChat = false), seen)

        assertNull(result, "没有会话时必须整体跳过")
        assertTrue(seen.isEmpty(), "跳过时不得往 seen-set 里写东西")
    }

    @Test
    fun `a backgrounded chat is not marked read`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(input(messages = listOf(msg("m1")), isActiveChat = false), seen)

        assertNull(result, "当前不在这个会话里绝不能标已读（否则切走就全变已读）")
        assertTrue(seen.isEmpty(), "跳过时不得污染 seen-set")
    }

    @Test
    fun `a placeholder owner is rejected`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val blank = ChatReadWatermarkPolicy.plan(input(messages = listOf(msg("m1")), ownerUserId = ""), seen)
        assertNull(blank, "owner 为空必须跳过")

        val placeholder = ChatReadWatermarkPolicy.plan(
            input(messages = listOf(msg("m1")), ownerUserId = ChatReadWatermarkPolicy.PLACEHOLDER_OWNER),
            seen,
        )
        assertNull(placeholder, "占位 owner（\"me\"）必须跳过——那是未登录/测试态")
        assertTrue(seen.isEmpty(), "被跳过时 seen-set 必须保持干净")
    }

    @Test
    fun `a stale session gate blocks marking`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(
            input(messages = listOf(msg("m1")), sessionMayContinue = false),
            seen,
        )
        assertNull(result, "会话门禁不通过（已登出/切号）绝不能标已读")
        assertTrue(seen.isEmpty(), "门禁不通过时不得记录 seen")
    }

    // ─── 哪些算新未读 ───

    @Test
    fun `my own messages are never unread`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(
            input(messages = listOf(msg("m1", senderId = me), msg("m2", senderId = me))),
            seen,
        )
        assertNull(result, "自己的消息不该产生已读回执")
        assertTrue(seen.isEmpty(), "自己的消息不该进 seen-set")
    }

    @Test
    fun `sender key distributions are never unread`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(
            input(messages = listOf(msg("sk1", type = MessageType.SK_DIST))),
            seen,
        )
        assertNull(result, "SK_DIST 不得计入未读（它是密钥材料，不是消息）")
        assertTrue(seen.isEmpty(), "SK_DIST 不该进 seen-set")
    }

    @Test
    fun `already read messages are not re-reported`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(
            input(messages = listOf(msg("m1", status = MessageStatus.READ))),
            seen,
        )
        assertNull(result, "已读消息不该再触发回执")
        assertTrue(seen.isEmpty(), "已读消息不该进 seen-set")
    }

    @Test
    fun `each message is reported exactly once across state changes`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val messages = listOf(msg("m1", timestamp = 10L))

        val first = ChatReadWatermarkPolicy.plan(input(messages), seen)
        assertTrue(first != null, "第一次必须报")
        assertEquals(setOf("m1"), seen.snapshot())

        // 消息仍在列表里、状态也没变 → 不该重复报
        val second = ChatReadWatermarkPolicy.plan(input(messages), seen)
        assertNull(second, "seen-set 必须保证同一条只报一次（否则状态每次变动都重发回执）")

        // 状态变成 READ 之后也不再报
        val readNow = listOf(msg("m1", timestamp = 10L, status = MessageStatus.READ))
        val third = ChatReadWatermarkPolicy.plan(input(readNow), seen)
        assertNull(third, "标已读之后不该再报")
    }

    @Test
    fun `a mixed batch reports only the qualifying messages`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val messages = listOf(
            msg("keep1", timestamp = 10L),
            msg("mine", senderId = me, timestamp = 11L),
            msg("sk", type = MessageType.SK_DIST, timestamp = 12L),
            msg("already", status = MessageStatus.READ, timestamp = 13L),
            msg("keep2", timestamp = 14L),
        )
        val result = ChatReadWatermarkPolicy.plan(input(messages), seen)

        assertTrue(result != null, "有合格未读就必须给计划")
        assertEquals(listOf("keep1", "keep2"), result!!.unreadIds.toList(), "只有合格未读该被报")
        assertEquals("keep2", result.watermarkMessageId, "水印应取时间最新的那条")
        assertEquals(14L, result.watermarkTimestamp)
        assertEquals(setOf("keep1", "keep2"), seen.snapshot(), "只有合格未读该进 seen-set")
    }

    // ─── 水印选择 ───

    @Test
    fun `the watermark is the newest by timestamp`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(
            input(
                listOf(
                    msg("old", timestamp = 100L),
                    msg("new", timestamp = 900L),
                    msg("mid", timestamp = 500L),
                ),
            ),
            seen,
        )
        assertEquals("new", result!!.watermarkMessageId)
        assertEquals(900L, result.watermarkTimestamp)
    }

    @Test
    fun `ties on timestamp are broken by id so the watermark is stable`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(
            input(listOf(msg("b", timestamp = 100L), msg("a", timestamp = 100L), msg("c", timestamp = 100L))),
            seen,
        )
        // timestamp 全相同时必须按 id 稳定决胜，不能因列表顺序抖动而变
        assertEquals("c", result!!.watermarkMessageId, "同 timestamp 时按 id 取稳定结果")
    }

    @Test
    fun `the watermark is the message to mark read through`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val result = ChatReadWatermarkPolicy.plan(input(listOf(msg("m1", timestamp = 5L))), seen)

        assertEquals("m1", result!!.watermarkMessageId, "水印消息 id 必须可用于 throughMessageId")
        assertEquals(5L, result.watermarkTimestamp, "水印时间戳必须可用于 throughTimestamp")
    }

    // ─── 失败回滚 ───

    @Test
    fun `a failed receipt enqueue rolls back so the next pass retries`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        val plan = ChatReadWatermarkPolicy.plan(input(listOf(msg("m1", timestamp = 1L))), seen)!!
        assertEquals(setOf("m1"), seen.snapshot())

        // 入队失败：必须把 seen 撤掉、把 lastMessagesSeen 置空，否则下次状态不再变化就永远不会重试
        ChatReadWatermarkPolicy.rollbackAfterFailure(seen, plan)

        assertTrue(seen.isEmpty(), "失败后 seen-set 必须回滚，否则这条消息水远不会重试")
    }

    @Test
    fun `rollback only removes what this plan claimed`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        // 先有一批已成功上报的
        ChatReadWatermarkPolicy.plan(input(listOf(msg("done", timestamp = 1L))), seen)
        // 再来一批，这批失败
        val failed = ChatReadWatermarkPolicy.plan(input(listOf(msg("done", timestamp = 1L), msg("retry", timestamp = 2L))), seen)!!
        ChatReadWatermarkPolicy.rollbackAfterFailure(seen, failed)

        assertEquals(setOf("done"), seen.snapshot(), "回滚只能撤自己那批，不能把已成功的也撤掉")
    }

    // ─── SeenSet 本身 ───

    @Test
    fun `seen set add is idempotent and reports membership`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        assertFalse(seen.contains("m1"))

        seen.add("m1")
        seen.add("m1")

        assertEquals(setOf("m1"), seen.snapshot())
        assertTrue(seen.contains("m1"))
    }

    @Test
    fun `seen set removeAll drops exactly the given ids`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        seen.addAll(listOf("a", "b", "c"))

        seen.removeAll(listOf("a", "c"))

        assertEquals(setOf("b"), seen.snapshot())
    }

    @Test
    fun `an empty unread batch yields no plan`() {
        val seen = ChatReadWatermarkPolicy.SeenSet()
        // 全部是自己的消息 → 没有合格未读 → 没有计划
        val result = ChatReadWatermarkPolicy.plan(
            input(listOf(msg("m1", senderId = me), msg("m2", senderId = me))),
            seen,
        )
        assertNull(result, "没有合格未读时不该有计划")
    }
}
