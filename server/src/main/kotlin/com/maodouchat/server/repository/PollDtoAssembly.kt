package com.maodouchat.server.repository

// 群投票 DTO 组装簇：结果聚合与校验 helpers（原 PollVoting 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

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
    pollOptionsJson.decodeFromString<List<String>>(row[GroupPolls.optionsJson])
}.getOrDefault(emptyList())


internal fun PollRepository.isMemberInTransaction(chatId: String, userId: String): Boolean =
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0
