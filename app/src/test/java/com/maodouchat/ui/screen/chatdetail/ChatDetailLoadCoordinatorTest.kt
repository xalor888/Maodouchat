package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G65 安全网：`ChatDetailLoadCoordinator` 的纯决策契约。
 *
 * 这些判定此前全部内联在 `ChatDetailViewModel.loadChat()`（178 行）里，**一个用例都没有**。
 * 而它们恰好是最容易在后续改动中被静默破坏的部分：
 * - 群成员 revision 上升时不失效 Sender Key → 群消息解不开，且**没有任何提示**；
 * - revision 不变/下降时误失效 → 每次进聊天都重排密钥，白白耗电与流量；
 * - 未读分隔线算错 → 「以下为未读消息」标错位置，用户以为看全了。
 *
 * 因为是纯函数（无 Coroutine、无 Room、无 ApiService），这些用例跑得快且完全确定。
 */
class ChatDetailLoadCoordinatorTest {

    private val me = "me"

    private fun chat(
        id: String = "c1",
        isGroup: Boolean,
        revision: Long = 0L,
        participants: List<User> = listOf(User(id = "me", name = "Me"), User(id = "u2", name = "U2")),
        groupName: String? = null,
        chatType: String? = null,
    ) = Chat(
        id = id,
        participants = participants,
        isGroup = isGroup,
        memberRevision = revision,
        groupName = groupName,
        // isSecret 是由 chatType 推导出来的（chatType == "SECRET"），不是独立入参
        chatType = chatType ?: if (isGroup) "GROUP" else "DIRECT",
    )

    private fun plan(
        chat: Chat,
        previousRevision: Long?,
        currentUserId: String = me,
        fromCache: Boolean = false,
        currentState: ChatDetailUiState = ChatDetailUiState(),
    ) = ChatDetailLoadCoordinator.planChatLoad(
        input = ChatDetailLoadCoordinator.ChatLoadInput(
            chat = chat,
            previousRevision = previousRevision,
            currentUserId = currentUserId,
            fromCache = fromCache,
        ),
        currentState = currentState,
        // 测试不碰 Android 资源：给一个可读的假格式化器
        formatGroupName = { "群聊" },
        formatMemberCount = { count -> "$count 人" },
        formatRevisionWarning = { "群成员已变更" },
    )

    // ─── 群成员 revision → Sender Key 失效 ───

    @Test
    fun `a rising group member revision must invalidate the sender key`() {
        val result = plan(chat(isGroup = true, revision = 5L), previousRevision = 4L)

        assertTrue(result.shouldInvalidateSenderKey, "revision 上升必须失效 Sender Key，否则群消息解不开")
        assertEquals("群成员已变更", result.revisionWarning, "失效时必须给用户可见的警示")
    }

    @Test
    fun `an unchanged or lower group revision must not invalidate the sender key`() {
        assertFalse(
            plan(chat(isGroup = true, revision = 4L), previousRevision = 4L).shouldInvalidateSenderKey,
            "revision 不变时误失效会白白重排密钥",
        )
        assertFalse(
            plan(chat(isGroup = true, revision = 3L), previousRevision = 4L).shouldInvalidateSenderKey,
            "revision 下降（服务端回退）不应失效",
        )
        assertNull(plan(chat(isGroup = true, revision = 4L), previousRevision = 4L).revisionWarning)
    }

    @Test
    fun `a first-seen group chat has no previous revision so nothing is invalidated`() {
        val result = plan(chat(isGroup = true, revision = 9L), previousRevision = null)

        assertFalse(result.shouldInvalidateSenderKey, "首次进入没有基线，不能算「成员变更」")
        assertNull(result.revisionWarning)
    }

    @Test
    fun `a direct chat never invalidates the sender key even when revision rises`() {
        val result = plan(chat(isGroup = false, revision = 99L), previousRevision = 1L)

        assertFalse(result.shouldInvalidateSenderKey, "直发会话没有群 Sender Key 可失效")
    }

    // ─── 群分支的状态与副作用 ───

    @Test
    fun `the group branch fills group fields and asks for group-only side effects`() {
        val group = chat(id = "g1", isGroup = true, revision = 2L, groupName = "研发群")
        val result = plan(group, previousRevision = 2L)

        assertTrue(result.nextState.chatIsGroup, "群分支必须置 chatIsGroup")
        assertEquals("g1", result.nextState.chat?.id)
        assertEquals("研发群", result.nextState.contact.name, "群会话的 contact 应当是群本身")
        assertFalse(result.nextState.isSecretChat ?: false, "普通群不是密聊")

        val effects = result.effects
        assertTrue(effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.LoadGroupCandidates }, "群必须拉候选人")
        assertTrue(effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBotCommands }, "群必须刷新 bot 命令")
        assertTrue(
            effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.OccupySessionCipher && it.peerUserId == null },
            "群会话占session cipher 时 peerUserId 必须为 null",
        )
        assertFalse(
            effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBlockState },
            "群分支不该刷屏蔽状态（那是直聊的事）",
        )
    }

    @Test
    fun `a secret group keeps the secret flag`() {
        val result = plan(chat(id = "s1", isGroup = true, revision = 1L, chatType = "SECRET"), previousRevision = 1L)

        assertEquals(true, result.nextState.isSecretChat, "密聊标志必须带到 UI 状态")
    }

    // ─── 直聊分支的状态与副作用 ───

    @Test
    fun `the direct branch sets only contact fields and never group fields`() {
        val direct = chat(id = "d1", isGroup = false, revision = 0L)
        val result = plan(direct, previousRevision = null)

        assertFalse(result.nextState.chatIsGroup, "直聊不得置 chatIsGroup")
        assertEquals("u2", result.nextState.contact.id, "contact 必须是对端用户而不是我自己")
        assertEquals(false, result.nextState.isSecretChat)

        val effects = result.effects
        assertTrue(
            effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.OccupySessionCipher && it.peerUserId == "u2" },
            "直聊必须带对端 userId 占 session cipher",
        )
        assertTrue(
            effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBlockState && it.peerUserId == "u2" },
            "直聊必须刷屏蔽状态",
        )
        assertTrue(effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshScheduledMessages }, "直聊必须刷新定时消息")
        assertFalse(effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.LoadGroupCandidates }, "直聊不该拉群候选人")
    }

    @Test
    fun `a direct chat with a bot peer refreshes bot commands instead of identity safety`() {
        val botPeer = User(id = "bot_abc", name = "Bot")
        val direct = chat(id = "d2", isGroup = false, revision = 0L, participants = listOf(User(id = "me", name = "Me"), botPeer))
        val result = plan(direct, previousRevision = null)

        assertTrue(
            result.effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBotCommands },
            "对端是 bot 时必须刷新 bot 命令",
        )
        assertFalse(
            result.effects.any {
                it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshIdentitySafety
            },
            "对端是 bot 时不该走身份验证刷新",
        )
    }

    @Test
    fun `a direct chat with a human peer refreshes identity safety instead of bot commands`() {
        val result = plan(chat(id = "d3", isGroup = false, revision = 0L), previousRevision = null)

        assertTrue(
            result.effects.any {
                it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshIdentitySafety &&
                    it.peerUserId == "u2"
            },
            "对端是人时必须刷新身份验证状态",
        )
        assertFalse(
            result.effects.any { it is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBotCommands },
            "对端是人时不该刷 bot 命令",
        )
    }

    @Test
    fun `a direct chat with no peer yields no side effects and no contact`() {
        // 只有我自己在参与者里（异常数据）：不得崩，也不得安排任何副作用
        val lonely = chat(id = "d4", isGroup = false, revision = 0L, participants = listOf(User(id = "me", name = "Me")))
        val result = plan(lonely, previousRevision = null)

        assertTrue(result.effects.isEmpty(), "没有对端时不该安排副作用，实际=${result.effects}")
        assertFalse(result.nextState.chatIsGroup)
    }

    // ─── 缓存回退 ───

    @Test
    fun `a cached group chat still drives the group branch`() {
        val result = plan(chat(id = "g2", isGroup = true, revision = 3L), previousRevision = 3L, fromCache = true)

        assertTrue(result.fromCache, "必须标记这是缓存回退，调用方据此决定是否提示")
        assertTrue(result.nextState.chatIsGroup)
        assertFalse(result.shouldInvalidateSenderKey, "缓存路径同样按 revision 判定，不该误失效")
    }

    // ─── loading 收尾 ───

    @Test
    fun `every successful plan clears loading and the initial error`() {
        val state = ChatDetailUiState(isLoading = true, initialLoadError = "上次失败了")
        val result = plan(chat(isGroup = true, revision = 1L), previousRevision = 1L, currentState = state)

        assertFalse(result.nextState.isLoading, "加载成功后 loading 必须收起")
        assertNull(result.nextState.initialLoadError, "加载成功后旧错误必须清掉")
    }

    @Test
    fun `the no-cache failure plan keeps the error visible`() {
        val plan = ChatDetailLoadCoordinator.planLoadFailure(
            currentState = ChatDetailUiState(isLoading = true),
            formatError = { "加载失败" },
        )
        assertFalse(plan.isLoading, "失败后 loading 必须收起")
        assertEquals("加载失败", plan.initialLoadError, "无缓存失败必须留下可诊断错误")
    }

    // ─── 未读分隔线 ───

    @Test
    fun `unread separator marks the oldest unread message`() {
        val messages = (1..5).map { Message(id = "m$it", chatId = "c1", senderId = "u2", content = "", type = MessageType.TEXT, timestamp = it.toLong()) }

        // 未读 2 条 → 分隔线应落在第 4 条（倒数第 2 条）之前
        assertEquals("m4", ChatDetailLoadCoordinator.unreadSeparatorId(unreadCount = 2, messages = messages))
    }

    @Test
    fun `unread separator ignores control messages when counting`() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u2", content = "", type = MessageType.TEXT, timestamp = 1L),
            Message(id = "sk1", chatId = "c1", senderId = "me", content = "", type = MessageType.SK_DIST, timestamp = 2L),
            Message(id = "m2", chatId = "c1", senderId = "u2", content = "", type = MessageType.TEXT, timestamp = 3L),
        )

        // 未读 1 条，但 SK_DIST 不参与分母 → 分隔线落在 m2
        assertEquals("m2", ChatDetailLoadCoordinator.unreadSeparatorId(unreadCount = 1, messages = messages))
    }

    @Test
    fun `unread separator is absent when there is nothing unread or everything is unread`() {
        val messages = (1..3).map { Message(id = "m$it", chatId = "c1", senderId = "u2", content = "", type = MessageType.TEXT, timestamp = it.toLong()) }

        assertNull(ChatDetailLoadCoordinator.unreadSeparatorId(unreadCount = 0, messages = messages), "没有未读就不该有分隔线")
        assertNull(
            ChatDetailLoadCoordinator.unreadSeparatorId(unreadCount = 3, messages = messages),
            "全部未读时分隔线没有意义（整屏都是未读）",
        )
        assertNull(
            ChatDetailLoadCoordinator.unreadSeparatorId(unreadCount = 99, messages = messages),
            "未读数超过实际条数（脏数据）不得崩也不得给分隔线",
        )
    }
}

