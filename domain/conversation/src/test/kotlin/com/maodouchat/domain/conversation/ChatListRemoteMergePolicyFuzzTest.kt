package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ChatListRemoteMergePolicy 远端合并 fuzz（清单 931 行第十五块，簇 4）。
 *
 * 合并规则全部按生产文档逐字抄成测试内 oracle（不是直接复用生产函数本身，
 * 否则只测了接线没测语义；Python 迷你模型已用既有用例的手算值核对过 oracle）：
 * - 未读：active 恒 0；local 为空取 server（钳 ≥0）；local 已读 0 且未手动标未读、
 *   本地尾时间 > 0 且 server 尾不更新 → 守 0；其余取 server/local 较大者；
 * - 设置：local.settingsUpdatedAt 严格更新才赢，否则 server 快照（等值取 server）；
 * - 标记：active 恒 false；local 为空取合并后设置里的 marked；local 乐观 true 未上 server 时保留；
 * - 参与者：群聊全留保序；单聊滤掉 currentUserId，滤空则回落全量；
 * - id / lastMessage / lastMessageTime / 群元数据 / chatType / 消失秒数全部透传 dto，
 *   lastMessageType 走 MessageType.fromWire（未知串回 TEXT，永不抛）。
 */
class ChatListRemoteMergePolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 150
        private val ids = listOf("c1", "c2", "c3", "me", "u2", "u3", "")
        private val lastMessageTypes = listOf("TEXT", "IMAGE", "text", "VIDEO", "FILE", "GARBAGE", "", "MARKDOWN")
        private val chatTypes = listOf("DIRECT", "GROUP", "CHANNEL", "SECRET", "weird")
        private val words = listOf("hi", "hello", "你好", "", "a b", "long message here", "🎉")
    }

    private fun randomId(random: Random): String = ids[random.nextInt(ids.size)]

    private fun randomUser(random: Random): UserDto =
        UserDto(id = randomId(random), name = words[random.nextInt(words.size)])

    private fun randomDto(random: Random): ChatDto {
        val participantCount = random.nextInt(0, 5)
        return ChatDto(
            id = randomId(random),
            participants = List(participantCount) { randomUser(random) },
            lastMessage = words[random.nextInt(words.size)],
            lastMessageType = lastMessageTypes[random.nextInt(lastMessageTypes.size)],
            lastMessageTime = random.nextLong(0, 1001L),
            unreadCount = random.nextInt(-5, 16),
            isGroup = random.nextBoolean(),
            chatType = chatTypes[random.nextInt(chatTypes.size)],
            groupName = if (random.nextBoolean()) words[random.nextInt(words.size)] else null,
            groupAnnouncement = if (random.nextBoolean()) words[random.nextInt(words.size)] else null,
            groupAvatar = if (random.nextBoolean()) "ava" else null,
            memberRevision = random.nextLong(0, 11L),
            pinnedAt = random.nextLong(0, 1001L),
            notificationsMuted = random.nextBoolean(),
            archived = random.nextBoolean(),
            markedUnread = random.nextBoolean(),
            settingsUpdatedAt = random.nextLong(0, 1001L),
            disappearingMessageSeconds = random.nextInt(0, 3601),
        )
    }

    private fun randomLocal(random: Random): Chat? {
        if (random.nextInt(0, 3) == 0) return null
        return Chat(
            id = randomId(random),
            unreadCount = random.nextInt(-5, 16),
            lastMessageTime = random.nextLong(0, 1001L),
            pinnedAt = random.nextLong(0, 1001L),
            notificationsMuted = random.nextBoolean(),
            archived = random.nextBoolean(),
            markedUnread = random.nextBoolean(),
            settingsUpdatedAt = random.nextLong(0, 1001L),
        )
    }

    private fun expectedUnread(dto: ChatDto, local: Chat?, isActiveChat: Boolean): Int {
        if (isActiveChat) return 0
        val server = dto.unreadCount.coerceAtLeast(0)
        val localUnread = local?.unreadCount?.coerceAtLeast(0) ?: return server
        if (localUnread == 0 && !local.markedUnread &&
            local.lastMessageTime > 0L && dto.lastMessageTime <= local.lastMessageTime
        ) {
            return 0
        }
        return maxOf(server, localUnread)
    }

    private fun expectedMarked(
        dto: ChatDto,
        local: Chat?,
        isActiveChat: Boolean,
        settingsMarked: Boolean,
    ): Boolean {
        if (isActiveChat) return false
        val localMarked = local?.markedUnread ?: return settingsMarked
        if (localMarked && !settingsMarked) return true
        return settingsMarked
    }

    private fun assertMergedMatchesOracle(
        dto: ChatDto,
        local: Chat?,
        currentUserId: String,
        isActiveChat: Boolean,
        iteration: Int,
    ) {
        val merged = ChatListRemoteMergePolicy.mergeRemoteChat(dto, local, currentUserId, isActiveChat)
        val again = ChatListRemoteMergePolicy.mergeRemoteChat(dto, local, currentUserId, isActiveChat)
        assertEquals(merged, again, "fuzz 迭代 #$iteration：同一输入两次合并必须相同")
        assertEquals(dto.id, merged.id, "fuzz 迭代 #$iteration：id 必须透传 dto")
        assertEquals(dto.lastMessage, merged.lastMessage, "fuzz 迭代 #$iteration：lastMessage 必须透传 dto")
        assertEquals(dto.lastMessageTime, merged.lastMessageTime, "fuzz 迭代 #$iteration：lastMessageTime 必须透传 dto")
        assertEquals(
            MessageType.fromWire(dto.lastMessageType), merged.lastMessageType,
            "fuzz 迭代 #$iteration：lastMessageType 必须走 fromWire"
        )
        val dtoIds = dto.participants.map { it.id }
        val expectedParticipants = if (dto.isGroup) {
            dtoIds
        } else {
            dtoIds.filter { it != currentUserId }.ifEmpty { dtoIds }
        }
        assertEquals(
            expectedParticipants, merged.participants.map { it.id },
            "fuzz 迭代 #$iteration：参与者过滤规则不符，dto=$dtoIds isGroup=${dto.isGroup} me=$currentUserId"
        )
        assertEquals(
            expectedUnread(dto, local, isActiveChat), merged.unreadCount,
            "fuzz 迭代 #$iteration：未读合并规则不符"
        )
        val serverWins = local == null || local.settingsUpdatedAt <= dto.settingsUpdatedAt
        assertEquals(
            if (serverWins) dto.pinnedAt else local!!.pinnedAt, merged.pinnedAt,
            "fuzz 迭代 #$iteration：设置胜者 pinnedAt 不符"
        )
        assertEquals(
            if (serverWins) dto.notificationsMuted else local!!.notificationsMuted, merged.notificationsMuted,
            "fuzz 迭代 #$iteration：设置胜者 notificationsMuted 不符"
        )
        assertEquals(
            if (serverWins) dto.archived else local!!.archived, merged.archived,
            "fuzz 迭代 #$iteration：设置胜者 archived 不符"
        )
        assertEquals(
            if (serverWins) dto.settingsUpdatedAt else local!!.settingsUpdatedAt, merged.settingsUpdatedAt,
            "fuzz 迭代 #$iteration：设置胜者 settingsUpdatedAt 不符"
        )
        val settingsMarked = if (serverWins) dto.markedUnread else local!!.markedUnread
        assertEquals(
            expectedMarked(dto, local, isActiveChat, settingsMarked), merged.markedUnread,
            "fuzz 迭代 #$iteration：标记合并规则不符"
        )
        assertEquals(dto.groupName, merged.groupName, "fuzz 迭代 #$iteration：groupName 必须透传 dto")
        assertEquals(dto.groupAnnouncement, merged.groupAnnouncement, "fuzz 迭代 #$iteration：groupAnnouncement 必须透传 dto")
        assertEquals(dto.groupAvatar, merged.groupAvatar, "fuzz 迭代 #$iteration：groupAvatar 必须透传 dto")
        assertEquals(dto.memberRevision, merged.memberRevision, "fuzz 迭代 #$iteration：memberRevision 必须透传 dto")
        assertEquals(dto.chatType, merged.chatType, "fuzz 迭代 #$iteration：chatType 必须透传 dto")
        assertEquals(dto.isGroup, merged.isGroup, "fuzz 迭代 #$iteration：isGroup 必须透传 dto")
        assertEquals(
            dto.disappearingMessageSeconds, merged.disappearingMessageSeconds,
            "fuzz 迭代 #$iteration：disappearingMessageSeconds 必须透传 dto"
        )
    }

    @Test
    fun `mergeRemoteChatMatchesOracleOnRandomInput`() {
        val random = Random(0x15_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val dto = randomDto(random)
            val local = randomLocal(random)
            val currentUserId = if (random.nextInt(0, 4) == 0) "other" else "me"
            assertMergedMatchesOracle(dto, local, currentUserId, random.nextBoolean(), i)
        }
    }

    @Test
    fun `activeChatAlwaysClearsUnreadAndMarked`() {
        val random = Random(0x15_2026)
        repeat(100) { i ->
            val merged = ChatListRemoteMergePolicy.mergeRemoteChat(
                dto = randomDto(random),
                local = randomLocal(random),
                currentUserId = "me",
                isActiveChat = true,
            )
            assertEquals(0, merged.unreadCount, "fuzz 迭代 #$i：active 聊天未读必须为 0")
            assertEquals(false, merged.markedUnread, "fuzz 迭代 #$i：active 聊天标记未读必须清除")
        }
    }

    @Test
    fun `filterDeletedAndStaleIdsMatchOracle`() {
        val random = Random(0x15_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val chats = List(random.nextInt(0, 8)) { Chat(id = randomId(random)) }
            val deleted = ids.filter { random.nextBoolean() }.toSet()
            assertEquals(
                chats.filterNot { deleted.contains(it.id) },
                ChatListRemoteMergePolicy.filterDeleted(chats, deleted),
                "fuzz 迭代 #$i：filterDeleted 必须等于 filterNot oracle"
            )
            val localIds = List(random.nextInt(0, 8)) { randomId(random) }
            val serverIds = ids.filter { random.nextBoolean() }.toSet()
            assertEquals(
                localIds.filterNot(serverIds::contains),
                ChatListRemoteMergePolicy.staleChatIds(localIds, serverIds),
                "fuzz 迭代 #$i：staleChatIds 必须等于集合差且保序"
            )
        }
    }

    @Test
    fun `nextErrorOnFailureMatchesTable`() {
        val random = Random(0x15_2026)
        val messages = listOf(null, "old", "", "  ", "new", "failure happened")
        repeat(RANDOM_ITERATIONS) { i ->
            val showLoading = random.nextBoolean()
            val rateLimited = random.nextBoolean()
            val currentError = messages[random.nextInt(messages.size)]
            val failureMessage = messages[random.nextInt(messages.size)]
            val expected = if (!showLoading || rateLimited) {
                currentError
            } else {
                failureMessage?.takeIf { it.isNotBlank() } ?: "fallback"
            }
            assertEquals(
                expected,
                ChatListRemoteMergePolicy.nextErrorOnFailure(
                    showLoading = showLoading,
                    rateLimited = rateLimited,
                    currentError = currentError,
                    failureMessage = failureMessage,
                    fallback = "fallback",
                ),
                "fuzz 迭代 #$i：nextErrorOnFailure 判定表不符"
            )
        }
        // 钉住边界：静默/限流时 currentError 为 null 也守 null，不回 fallback。
        assertNull(
            ChatListRemoteMergePolicy.nextErrorOnFailure(false, false, null, "new", "fallback"),
            "静默失败 currentError 为 null 时必须守 null"
        )
    }

    @Test
    fun `mergeNeverThrowsOnRandomInput`() {
        val random = Random(0x15_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            // 只断言不抛：随机 DTO/local/active 组合不得把异常漏给调用方。
            ChatListRemoteMergePolicy.mergeRemoteChat(
                dto = randomDto(random),
                local = randomLocal(random),
                currentUserId = randomId(random),
                isActiveChat = random.nextBoolean(),
            )
        }
    }
}
