package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** 群投票写入簇：创建 / 投票 / 关闭（含 DTO 组装与校验 helpers）。 */

fun PollRepository.createPoll(
    chatId: String,
    creatorId: String,
    question: String,
    options: List<String>,
    multi: Boolean,
    anonymous: Boolean,
    closesAt: Long?,
    requireBotDeliverable: Boolean = false
): PollRepository.PollDto? {
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
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(creatorId, now)) {
            return@transaction null
        }
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

fun PollRepository.vote(
    pollId: String,
    userId: String,
    optionIndexes: List<Int>,
    requireBotDeliverable: Boolean = false
): PollRepository.PollDto? {
    if (!isValidId(pollId) || !isValidId(userId) || optionIndexes.isEmpty() || optionIndexes.size > MAX_OPTIONS) return null
    return transaction {
        val now = System.currentTimeMillis()
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(userId, now)) {
            return@transaction null
        }
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

fun PollRepository.closePoll(
    pollId: String,
    userId: String,
    requireBotDeliverable: Boolean = false
): PollRepository.PollDto? {
    if (!isValidId(pollId) || !isValidId(userId)) return null
    return transaction {
        val now = System.currentTimeMillis()
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(userId, now)) {
            return@transaction null
        }
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

internal fun PollRepository.toPollDto(
    row: ResultRow,
    viewerId: String,
    forceClosed: Boolean = false,
    preloadedVotes: List<ResultRow> = emptyList(),
    blocked: Set<String>? = null
): PollRepository.PollDto {
    val pollId = row[GroupPolls.id]
    val options = decodeOptions(row)
    val blocked = blocked ?: ConversationVisibility.blockedUserIdsInTx(viewerId)
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
    return PollRepository.PollDto(
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

internal fun PollRepository.decodeOptions(row: org.jetbrains.exposed.sql.ResultRow): List<String> = runCatching {
    json.decodeFromString<List<String>>(row[GroupPolls.optionsJson])
}.getOrDefault(emptyList())

internal fun PollRepository.isMemberInTransaction(chatId: String, userId: String): Boolean =
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0

internal fun PollRepository.isValidId(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val MIN_OPTIONS = 2

private const val MAX_OPTIONS = 12

private const val MAX_QUESTION_LENGTH = 200

private const val MAX_OPTION_LENGTH = 80

private const val MAX_POLL_DURATION_MS = 30L * 24L * 60L * 60L * 1_000L
