package com.maodouchat.server.repository

// 管理导出·投票簇：投票与投票记录元数据导出（原 AdminExportSocial 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminExportRepository.polls(limit: Int): List<List<Any?>> = transaction {
    val polls = GroupPolls.selectAll()
        .orderBy(
            GroupPolls.createdAt to SortOrder.DESC,
            GroupPolls.id to SortOrder.DESC,
        )
        .limit(limit)
        .toList()
    // 8.48 修复 H6：批量 count（此前逐投票查询 → limit 1 万次查询）
    val pollIds = polls.map { it[GroupPolls.id] }
    val votesByPoll = if (pollIds.isEmpty()) {
        emptyMap()
    } else {
        GroupPollVotes
            .slice(GroupPollVotes.pollId, GroupPollVotes.userId.count())
            .selectAll()
            .where { GroupPollVotes.pollId inList pollIds }
            .groupBy(GroupPollVotes.pollId)
            .associate { it[GroupPollVotes.pollId] to it[GroupPollVotes.userId.count()].toLong() }
    }
    polls.map { row ->
        val id = row[GroupPolls.id]
        val votes = votesByPoll[id] ?: 0L
        listOf(
            id,
            row[GroupPolls.chatId],
            row[GroupPolls.creatorId],
            row[GroupPolls.question].take(120),
            row[GroupPolls.multi].toString(),
            row[GroupPolls.anonymous].toString(),
            row[GroupPolls.closed].toString(),
            votes.toString(),
            row[GroupPolls.createdAt].toString(),
            (row[GroupPolls.closesAt] ?: 0L).toString(),
        )
    }
}

fun AdminExportRepository.pollVotes(limit: Int): List<List<Any?>> = transaction {
    GroupPollVotes.selectAll()
        .limit(limit)
        .map { row ->
            listOf(
                row[GroupPollVotes.pollId],
                row[GroupPollVotes.userId],
                row[GroupPollVotes.optionIndex].toString(),
                row[GroupPollVotes.votedAt].toString(),
            )
        }
}
