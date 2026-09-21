package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G64 安全网：`ChatReadReceiptCoordinator` 的行为契约。
 *
 * 这个类在改造前**一个用例都没有**，而它正好是 `ui/` 层唯一一处直连 `MessagingV2Dao` 的地方。
 * 不先补网就把 DAO 换成端口，等于在没绑安全带的情况下拆方向盘。
 *
 * 覆盖五条容易在「顺手改依赖」时被破坏的语义：
 * 1. 回执开关关闭时绝不读库（省电 + 隐私）；
 * 2. 非发送者看不到别人的回执（`ReadReceiptPolicy.canViewReceipts`）；
 * 3. 群已读数只统计「我发的、非密钥分发、非发送中/失败」的消息；
 * 4. 读库异常不得吞掉——必须落到 UI 上可诊断的字段里；
 * 5. `observe` 走 Flow 且不额外同步读库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReadReceiptCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    /** 测试用的端口实现：直接伪造，不碰 Room。 */
    private class FakeReceiptSource : ReadReceiptSource {
        val receipts = mutableListOf<MessagingV2ReceiptEntity>()
        var getCalls = 0
        var observeCalls = 0
        var throwOnGet: Throwable? = null
        val observed = MutableStateFlow<List<MessagingV2ReceiptEntity>>(emptyList())

        override suspend fun getReceiptsForMessage(
            ownerUserId: String,
            messageId: String,
        ): List<MessagingV2ReceiptEntity> {
            getCalls++
            throwOnGet?.let { throw it }
            return receipts.filter { it.messageId == messageId }
        }

        override fun observeReceiptsForConversation(
            ownerUserId: String,
            conversationId: String,
        ): Flow<List<MessagingV2ReceiptEntity>> {
            observeCalls++
            return observed
        }
    }

    private fun user(id: String, online: Boolean = false) =
        User(id = id, name = id.uppercase(), isOnline = online)

    private fun message(id: String, senderId: String, type: MessageType, status: MessageStatus) =
        Message(id = id, chatId = "c1", senderId = senderId, content = "", type = type, timestamp = 1L, status = status)

    private fun state(
        messages: List<Message> = emptyList(),
        participants: List<User> = listOf(user("me"), user("u2"), user("u3")),
    ) = ChatDetailUiState(
        chatIsGroup = true,
        chat = Chat(id = "c1", participants = participants, isGroup = true),
        messages = messages,
    )

    private fun coordinator(
        source: ReadReceiptSource,
        initial: ChatDetailUiState,
        receiptsEnabled: Boolean = true,
    ): Triple<ChatReadReceiptCoordinator, MutableList<ChatDetailUiState>, () -> ChatDetailUiState> {
        var live = initial
        val seen = mutableListOf<ChatDetailUiState>()
        val coordinator = ChatReadReceiptCoordinator(
            scope = CoroutineScope(dispatcher),
            dao = source,
            currentUserId = { "me" },
            currentState = { live },
            updateState = { transform -> live = transform(live); seen += live },
            receiptsEnabled = { receiptsEnabled },
            errorMessage = { "读不到已读状态" },
        )
        return Triple(coordinator, seen) { live }
    }

    /** 协调器内部用 `withContext(Dispatchers.IO)`，测试调度器管不到它——用真实等待收敛。 */
    private suspend fun awaitQuiescence() {
        repeat(200) {
            kotlinx.coroutines.delay(10)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `receipts switch off means the database is never touched`() = runTest(dispatcher) {
        val source = FakeReceiptSource()
        val (coordinator, _, _) = coordinator(source, state(), receiptsEnabled = false)

        coordinator.loadDetails("m1")
        assertEquals(0, source.getCalls, "回执关着就不该读库")

        coordinator.prefetchRecentGroupCounts()
        assertEquals(0, source.getCalls, "预取也必须尊重开关")
    }

    @Test
    fun `a non sender never sees receipts for someone else's message`() = runTest(dispatcher) {
        val source = FakeReceiptSource()
        source.receipts += MessagingV2ReceiptEntity(
            ownerUserId = "me", messageId = "m1", conversationId = "c1",
            recipientUserId = "u2", readAt = 100L,
        )
        // 消息是 u2 发的，当前用户只是接收者
        val base = state(messages = listOf(message("m1", "u2", MessageType.TEXT, MessageStatus.SENT)))
        val (coordinator, _, _) = coordinator(source, base)

        coordinator.loadDetails("m1")

        assertEquals(0, source.getCalls, "非发送者不得触发回执查询")
    }

    @Test
    fun `group read count only counts my own deliverable messages`() = runTest(dispatcher) {
        val source = FakeReceiptSource()
        source.receipts += listOf(
            MessagingV2ReceiptEntity("me", "m1", "c1", "u2", readAt = 10L),
            MessagingV2ReceiptEntity("me", "m1", "c1", "u3", readAt = 11L),
        )
        val base = state(
            messages = listOf(
                message("m1", "me", MessageType.TEXT, MessageStatus.SENT),
                // SK 分发不该计入已读分母
                message("m2", "me", MessageType.SK_DIST, MessageStatus.SENT),
                // 发送中的也不该计入
                message("m3", "me", MessageType.TEXT, MessageStatus.SENDING),
                // 失败的不该计入
                message("m4", "me", MessageType.TEXT, MessageStatus.FAILED),
            ),
        )
        val (coordinator, seen, _) = coordinator(source, base)

        coordinator.prefetchRecentGroupCounts()
        awaitQuiescence()

        val counts = seen.lastOrNull()?.groupReadCounts.orEmpty()
        // m1：2 个已读 / 2 个可统计成员（u2、u3）
        assertEquals(2, counts["m1"]?.read, "m1 的已读数应当是 2")
        assertEquals(2, counts["m1"]?.total, "分母应当排除我自己")
        assertFalse(counts.containsKey("m2"), "SK 分发不得进入已读统计")
        assertFalse(counts.containsKey("m3"), "发送中的消息不得进入已读统计")
        assertFalse(counts.containsKey("m4"), "失败的消息不得进入已读统计")
    }

    @Test
    fun `a read failure surfaces instead of being swallowed`() = runTest(dispatcher) {
        val source = FakeReceiptSource().apply { throwOnGet = IllegalStateException("db exploded") }
        val base = state(messages = listOf(message("m1", "me", MessageType.TEXT, MessageStatus.SENT)))
        val (coordinator, seen, _) = coordinator(source, base)

        coordinator.loadDetails("m1")
        awaitQuiescence()

        val last = seen.lastOrNull()
        assertNotNull(last, "失败也必须更新 UI 状态")
        assertFalse(last.isLoadingReadReceipts, "失败后 loading 必须收起")
        assertEquals("读不到已读状态", last.groupEncryptionWarning, "异常必须落到 UI 可诊断字段")
    }

    @Test
    fun `observing a conversation refreshes group counts without extra reads`() = runTest(dispatcher) {
        val source = FakeReceiptSource()
        val base = state(messages = listOf(message("m1", "me", MessageType.TEXT, MessageStatus.SENT)))
        val (coordinator, seen, _) = coordinator(source, base)

        val job: Job? = coordinator.observe("c1")
        assertNotNull(job, "observe 必须返回 Job")
        assertTrue(job.isActive, "观察应当是活跃的")
        awaitQuiescence()
        assertEquals(0, source.getCalls, "observe 走 Flow，不应额外同步读库")
        assertEquals(1, source.observeCalls, "observe 必须订阅一次")

        source.observed.value = listOf(
            MessagingV2ReceiptEntity("me", "m1", "c1", "u2", readAt = 5L),
        )
        awaitQuiescence()

        val counts = seen.lastOrNull()?.groupReadCounts.orEmpty()
        assertEquals(1, counts["m1"]?.read, "Flow 推送后已读数必须刷新")
        assertEquals(2, counts["m1"]?.total, "分母仍是可统计成员数")

        job.cancel()
    }

    @Test
    fun `clear resets the receipt surface`() = runTest(dispatcher) {
        val source = FakeReceiptSource()
        val (coordinator, seen, _) = coordinator(source, state())

        coordinator.clear()

        val last = seen.lastOrNull()
        assertNotNull(last)
        assertTrue(last.readReceipts.isEmpty(), "clear 必须清空列表")
        assertFalse(last.isLoadingReadReceipts, "clear 必须收起 loading")
    }
}
