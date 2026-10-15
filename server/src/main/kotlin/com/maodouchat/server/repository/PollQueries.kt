package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 群投票查询簇：列表 / 快照 / 单条。 */

fun PollRepository.listChatPollSnapshots(chatId: String, limit: Int = 30, viewerId: String? = null): List<PollSnapshot> {
    if (chatId.isBlank()) return emptyList()
    return transaction {
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        val pollBase = GroupPolls.selectAll().where { GroupPolls.chatId eq chatId }
        val pollQuery = if (blocked.isEmpty()) pollBase
        else pollBase.andWhere { GroupPolls.creatorId notInList blocked.toList() }
        val polls = pollQuery
            .orderBy(GroupPolls.createdAt to SortOrder.DESC, GroupPolls.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .toList()
        val pollIds = polls.map { it[GroupPolls.id] }
        val votesByPoll = if (pollIds.isEmpty()) emptyMap() else
            GroupPollVotes.selectAll()
                .where { GroupPollVotes.pollId inList pollIds }
                .toList()
                .groupBy { it[GroupPollVotes.pollId] }
        polls.map { row ->
                val pollId = row[GroupPolls.id]
                val options = decodeOptions(row)
                val votes = votesByPoll[pollId].orEmpty().filter { it[GroupPollVotes.userId] !in blocked }
                val counts = IntArray(options.size)
                val voters = mutableSetOf<String>()
                val my = mutableListOf<Int>()
                for (v in votes) {
                    val idx = v[GroupPollVotes.optionIndex]
                    val uid = v[GroupPollVotes.userId]
                    if (idx in counts.indices) counts[idx]++
                    voters += uid
                    if (viewerId != null && uid == viewerId) my += idx
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
                    totalVoters = voters.size,
                    creatorId = row[GroupPolls.creatorId],
                    myVotes = my.distinct().sorted()
                )
            }
    }
}

fun PollRepository.listChatPolls(chatId: String, userId: String, limit: Int = 30): List<PollDto> {
    if (!isValidId(chatId) || !isValidId(userId)) return emptyList()
    return transaction {
        // 8.39：只读路径不加 FOR UPDATE（此前拿写锁，同一群投票列表/详情读取互相串行，
        // 并阻塞该群所有签到/接龙/PK 写事务）；写路径（createPoll/vote/closePoll）保留
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction emptyList()
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction emptyList()
        val blocked = ConversationVisibility.blockedUserIdsInTx(userId)
        val pollBase = GroupPolls.selectAll().where { GroupPolls.chatId eq chatId }
        val pollQuery = if (blocked.isEmpty()) pollBase
        else pollBase.andWhere { GroupPolls.creatorId notInList blocked.toList() }
        val polls = pollQuery
            .orderBy(GroupPolls.createdAt to SortOrder.DESC, GroupPolls.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .toList()
        // 8.48 修复 H4：批量取投票（此前 toPollDto 逐个 poll 全量载入 → N+1）
        val pollIds = polls.map { it[GroupPolls.id] }
        val votesByPoll = if (pollIds.isEmpty()) emptyMap() else
            GroupPollVotes.selectAll()
                .where { GroupPollVotes.pollId inList pollIds }
                .toList()
                .groupBy { it[GroupPollVotes.pollId] }
        polls.map {
            toPollDto(it, userId, preloadedVotes = votesByPoll[it[GroupPolls.id]].orEmpty(), blocked = blocked)
        }
    }
}

fun PollRepository.getPoll(pollId: String, viewerId: String): PollDto? = transaction {
    if (!isValidId(pollId) || !isValidId(viewerId)) return@transaction null
    val row = GroupPolls.selectAll().where { GroupPolls.id eq pollId }.firstOrNull() ?: return@transaction null
    val chatId = row[GroupPolls.chatId]
    val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
        ?: return@transaction null
    if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction null
    if (row[GroupPolls.creatorId] in ConversationVisibility.blockedUserIdsInTx(viewerId)) return@transaction null
    toPollDto(row, viewerId)
}
