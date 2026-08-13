package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 群投票扩展仓库（B3）。
 *
 * 投票 CRUD 主体复用 [GroupPlayRepository]（Routing.kt 已注册的 5 个投票端点保持不变），
 * 本仓库只提供投票之外的补充能力：
 *  1. 群投票列表同步快照（含 totalVoters/myVotes 富化，供 PollRouting 的 sync 端点使用）；
 *  2. 群成员名单查询，供 WS 广播「投票有更新」时定位在线收件人。
 *
 * 投票/签到的票选记录为群内公开元数据，明文存储，无需加密。
 */
object PollRepository {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 投票公开快照（广播给群成员时使用，字段与 GroupPlayRepository.PollDto 兼容的轻量版）。 */
    @Serializable
    data class PollSnapshot(
        val id: String,
        val chatId: String,
        val question: String,
        val options: List<String>,
        val multi: Boolean,
        val anonymous: Boolean,
        val closed: Boolean,
        val createdAt: Long,
        val closesAt: Long? = null,
        val counts: List<Int> = emptyList(),
        val totalVoters: Int = 0
    )

    /**
     * 拉取某群最新 N 个投票的公开快照（不含个人 myVotes，匿名安全）。
     * 用于客户端轮询同步与 WS 广播后的刷新。
     */
    fun listChatPollSnapshots(chatId: String, limit: Int = 30, viewerId: String? = null): List<PollSnapshot> {
        if (chatId.isBlank()) return emptyList()
        return transaction {
            val blocked = blockedUserIdsInTx(viewerId)
            GroupPolls.selectAll().where { GroupPolls.chatId eq chatId }
                .orderBy(GroupPolls.createdAt to SortOrder.DESC, GroupPolls.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .filterNot { it[GroupPolls.creatorId] in blocked }
                .map { row ->
                    val pollId = row[GroupPolls.id]
                    val options = decodeOptions(row)
                    val votes = GroupPollVotes
                        .selectAll()
                        .where { GroupPollVotes.pollId eq pollId }
                        .toList()
                        .filter { it[GroupPollVotes.userId] !in blocked }
                    val counts = IntArray(options.size)
                    val voters = mutableSetOf<String>()
                    for (v in votes) {
                        val idx = v[GroupPollVotes.optionIndex]
                        if (idx in counts.indices) counts[idx]++
                        voters += v[GroupPollVotes.userId]
                    }
                    PollSnapshot(
                        id = pollId,
                        chatId = row[GroupPolls.chatId],
                        question = row[GroupPolls.question],
                        options = options,
                        multi = row[GroupPolls.multi],
                        anonymous = row[GroupPolls.anonymous],
                        closed = row[GroupPolls.closed] || ((row[GroupPolls.closesAt] ?: Long.MAX_VALUE) <= System.currentTimeMillis()),
                        createdAt = row[GroupPolls.createdAt],
                        closesAt = row[GroupPolls.closesAt],
                        counts = counts.toList(),
                        totalVoters = voters.size
                    )
                }
        }
    }

    private fun blockedUserIdsInTx(viewerId: String?): Set<String> {
        if (viewerId.isNullOrBlank()) return emptySet()
        return BlockedUsers.selectAll()
            .where {
                (BlockedUsers.blockerId eq viewerId) or (BlockedUsers.blockedId eq viewerId)
            }
            .map { row ->
                if (row[BlockedUsers.blockerId] == viewerId) row[BlockedUsers.blockedId]
                else row[BlockedUsers.blockerId]
            }
            .toSet()
    }

    /**
     * 群成员名单（用于 WS fanout）。与 GroupPlayRepository.isMemberInTransaction
     * 一致：仅返回 ChatParticipants 中的真实成员，避免对已退群成员推送。
     */
    fun memberIds(chatId: String): List<String> {
        if (chatId.isBlank()) return emptyList()
        return transaction {
            ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }
                .map { it[ChatParticipants.userId] }
        }
    }

    /** 校验 chatId 是群聊。 */
    fun isGroupChat(chatId: String): Boolean = transaction {
        Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()?.get(Chats.isGroup) == true
    }

    /** 校验 userId 是 chatId 的成员。 */
    fun isMember(chatId: String, userId: String): Boolean = transaction {
        ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }.count() > 0
    }

    /** 校验 userId 在 chatId 中是否处于禁言期。 */
    fun isMuted(chatId: String, userId: String, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val mutedUntil = ChatParticipants.selectAll()
            .where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }
            .firstOrNull()
            ?.get(ChatParticipants.mutedUntil)
            ?: 0L
        mutedUntil > now
    }

    private fun decodeOptions(row: org.jetbrains.exposed.sql.ResultRow): List<String> = runCatching {
        json.decodeFromString<List<String>>(row[GroupPolls.optionsJson])
    }.getOrDefault(emptyList())
}
