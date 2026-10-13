package com.maodouchat.server.repository

// 群投票创建簇：投票创建、选项 JSON 编解码器与边界常量（原 PollMutations 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPolls
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

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
            it[GroupPolls.optionsJson] = pollOptionsJson.encodeToString(opts)
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

internal val pollOptionsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal const val MIN_OPTIONS = 2

internal const val MAX_OPTIONS = 12

internal const val MAX_QUESTION_LENGTH = 200

internal const val MAX_OPTION_LENGTH = 80

internal const val MAX_POLL_DURATION_MS = 30L * 24L * 60L * 60L * 1_000L
