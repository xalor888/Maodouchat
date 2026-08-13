package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

object GroupPlayRepository {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class PollDto(
        val id: String,
        val chatId: String,
        val creatorId: String,
        val question: String,
        val options: List<String>,
        val multi: Boolean,
        val anonymous: Boolean,
        val closed: Boolean,
        val createdAt: Long,
        val closesAt: Long? = null,
        val counts: List<Int> = emptyList(),
        val myVotes: List<Int> = emptyList(),
        val totalVoters: Int = 0
    )

    fun createPoll(
        chatId: String,
        creatorId: String,
        question: String,
        options: List<String>,
        multi: Boolean,
        anonymous: Boolean,
        closesAt: Long?
    ): PollDto? {
        val q = question.trim()
        val opts = options.map(String::trim)
        val now = System.currentTimeMillis()
        if (!isValidId(chatId) || !isValidId(creatorId)) return null
        if (q.isBlank() || q.length > MAX_QUESTION_LENGTH) return null
        if (opts.size !in MIN_OPTIONS..MAX_OPTIONS || opts.any { it.isBlank() || it.length > MAX_OPTION_LENGTH }) return null
        if (opts.map { it.lowercase() }.distinct().size != opts.size) return null
        if (closesAt != null && (closesAt <= now || closesAt - now > MAX_POLL_DURATION_MS)) return null
        val id = "poll_" + UUID.randomUUID().toString().replace("-", "").take(16)
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, creatorId)) return@transaction null
            GroupPolls.insert {
                it[GroupPolls.id] = id
                it[GroupPolls.chatId] = chatId
                it[GroupPolls.creatorId] = creatorId
                it[GroupPolls.question] = q
                it[GroupPolls.optionsJson] = json.encodeToString(opts)
                it[GroupPolls.multi] = multi
                it[GroupPolls.anonymous] = anonymous
                it[GroupPolls.closed] = false
                it[GroupPolls.createdAt] = now
                it[GroupPolls.closesAt] = closesAt
            }
            val row = GroupPolls.selectAll().where { GroupPolls.id eq id }.first()
            toPollDto(row, creatorId)
        }
    }

    fun vote(pollId: String, userId: String, optionIndexes: List<Int>): PollDto? {
        if (!isValidId(pollId) || !isValidId(userId) || optionIndexes.isEmpty() || optionIndexes.size > MAX_OPTIONS) return null
        return transaction {
            val probe = GroupPolls.selectAll().where { GroupPolls.id eq pollId }.firstOrNull()
                ?: return@transaction null
            val chatId = probe[GroupPolls.chatId]
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            val row = GroupPolls.selectAll().where { GroupPolls.id eq pollId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val options = decodeOptions(row)
            val valid = optionIndexes.distinct()
            if (valid.isEmpty() || valid.any { it !in options.indices }) return@transaction null
            if (!row[GroupPolls.multi] && valid.size > 1) return@transaction null
            val now = System.currentTimeMillis()
            val isClosed = row[GroupPolls.closed] || ((row[GroupPolls.closesAt] ?: Long.MAX_VALUE) <= now)
            // 已关闭的投票不是「成功但未变更」：返回 null，路由回 400，避免客户端误以为投票已计入。
            if (isClosed) return@transaction null
            GroupPollVotes.deleteWhere {
                (GroupPollVotes.pollId eq pollId) and (GroupPollVotes.userId eq userId)
            }
            for (idx in valid) {
                GroupPollVotes.insert {
                    it[GroupPollVotes.pollId] = pollId
                    it[GroupPollVotes.userId] = userId
                    it[GroupPollVotes.optionIndex] = idx
                    it[GroupPollVotes.votedAt] = now
                }
            }
            toPollDto(row, userId)
        }
    }

    fun closePoll(pollId: String, userId: String): PollDto? {
        if (!isValidId(pollId) || !isValidId(userId)) return null
        return transaction {
            val probe = GroupPolls.selectAll().where { GroupPolls.id eq pollId }.firstOrNull()
                ?: return@transaction null
            val chatId = probe[GroupPolls.chatId]
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            val row = GroupPolls.selectAll().where { GroupPolls.id eq pollId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (row[GroupPolls.creatorId] != userId) return@transaction null
            GroupPolls.update({ GroupPolls.id eq pollId }) {
                it[closed] = true
            }
            toPollDto(row, userId, forceClosed = true)
        }
    }

    fun listChatPolls(chatId: String, userId: String, limit: Int = 30): List<PollDto> {
        if (!isValidId(chatId) || !isValidId(userId)) return emptyList()
        return transaction {
            // 8.39：只读路径不加 FOR UPDATE（此前拿写锁，同一群投票列表/详情读取互相串行，
            // 并阻塞该群所有签到/接龙/PK 写事务）；写路径（createPoll/vote/closePoll）保留
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction emptyList()
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction emptyList()
            val polls = GroupPolls.selectAll().where { GroupPolls.chatId eq chatId }
                .orderBy(GroupPolls.createdAt to SortOrder.DESC, GroupPolls.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .filterNot { it[GroupPolls.creatorId] in blockedUserIdsInTx(userId) }
                .toList()
            // 8.48 修复 H4：批量取投票（此前 toPollDto 逐个 poll 全量载入 → N+1）
            val pollIds = polls.map { it[GroupPolls.id] }
            val votesByPoll = if (pollIds.isEmpty()) emptyMap() else
                GroupPollVotes.selectAll()
                    .where { GroupPollVotes.pollId inList pollIds }
                    .toList()
                    .groupBy { it[GroupPollVotes.pollId] }
            polls.map { toPollDto(it, userId, preloadedVotes = votesByPoll[it[GroupPolls.id]].orEmpty()) }
        }
    }

    fun getPoll(pollId: String, viewerId: String): PollDto? = transaction {
        if (!isValidId(pollId) || !isValidId(viewerId)) return@transaction null
        val row = GroupPolls.selectAll().where { GroupPolls.id eq pollId }.firstOrNull() ?: return@transaction null
        val chatId = row[GroupPolls.chatId]
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction null
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction null
        if (row[GroupPolls.creatorId] in blockedUserIdsInTx(viewerId)) return@transaction null
        toPollDto(row, viewerId)
    }

    private fun toPollDto(row: ResultRow, viewerId: String, forceClosed: Boolean = false, preloadedVotes: List<ResultRow> = emptyList()): PollDto {
        val pollId = row[GroupPolls.id]
        val options = decodeOptions(row)
        val blocked = blockedUserIdsInTx(viewerId)
        // 8.48：列表路径由调用方批量预取；单条路径（空）此处回查
        val votes = (if (preloadedVotes.isNotEmpty()) preloadedVotes else
            GroupPollVotes.selectAll().where { GroupPollVotes.pollId eq pollId }.toList()
            ).filter { it[GroupPollVotes.userId] !in blocked }
        val counts = IntArray(options.size)
        val voters = mutableSetOf<String>()
        val my = mutableListOf<Int>()
        for (v in votes) {
            val idx = v[GroupPollVotes.optionIndex]
            val uid = v[GroupPollVotes.userId]
            if (idx in counts.indices) counts[idx]++
            voters += uid
            if (uid == viewerId) my += idx
        }
        return PollDto(
            id = pollId,
            chatId = row[GroupPolls.chatId],
            creatorId = row[GroupPolls.creatorId],
            question = row[GroupPolls.question],
            options = options,
            multi = row[GroupPolls.multi],
            anonymous = row[GroupPolls.anonymous],
            closed = forceClosed || row[GroupPolls.closed] || ((row[GroupPolls.closesAt] ?: Long.MAX_VALUE) <= System.currentTimeMillis()),
            createdAt = row[GroupPolls.createdAt],
            closesAt = row[GroupPolls.closesAt],
            counts = counts.toList(),
            myVotes = my.distinct().sorted(),
            totalVoters = voters.size
        )
    }

    private fun decodeOptions(row: ResultRow): List<String> = runCatching {
        json.decodeFromString<List<String>>(row[GroupPolls.optionsJson])
    }.getOrDefault(emptyList())

    private fun blockedUserIdsInTx(viewerId: String): Set<String> {
        if (viewerId.isBlank()) return emptySet()
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

    private fun isMemberInTransaction(chatId: String, userId: String): Boolean =
        ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }.count() > 0

    private fun isValidId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private const val MIN_OPTIONS = 2
    private const val MAX_OPTIONS = 12
    private const val MAX_QUESTION_LENGTH = 200
    private const val MAX_OPTION_LENGTH = 80
    private const val MAX_POLL_DURATION_MS = 30L * 24L * 60L * 60L * 1_000L
}
