package com.maodouchat.server.repository

// 群投票投票与关闭簇：投票写入、关闭与 ID 格式校验（原 PollMutations 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

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

internal fun PollRepository.isValidId(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
