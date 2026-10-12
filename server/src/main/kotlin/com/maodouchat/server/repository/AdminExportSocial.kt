package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 好友关系图元数据 only — no message bodies。 */
fun AdminExportRepository.friendships(limit: Int): List<List<Any?>> = transaction {
    Friendships.selectAll()
        .orderBy(
            Friendships.createdAt to SortOrder.DESC,
            Friendships.userLowId to SortOrder.DESC,
            Friendships.userHighId to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[Friendships.userLowId],
                row[Friendships.userHighId],
                row[Friendships.createdAt].toString(),
            )
        }
}

/** 只有拉黑边 — no message bodies。 */
fun AdminExportRepository.blockedUsers(limit: Int): List<List<Any?>> = transaction {
    BlockedUsers.selectAll()
        .limit(limit)
        .map { row ->
            listOf(
                row[BlockedUsers.blockerId],
                row[BlockedUsers.blockedId],
            )
        }
}

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

/** 只导出元数据：token 只含前缀，绝不导出 tokenHash。 */
fun AdminExportRepository.bots(limit: Int): List<List<Any?>> = transaction {
    BotApps.selectAll()
        .orderBy(BotApps.createdAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
            listOf(
                row[BotApps.id],
                row[BotApps.name],
                row[BotApps.username],
                row[BotApps.ownerUserId],
                row[BotApps.tokenPrefix],
                row[BotApps.webhookUrl] ?: "",
                row[BotApps.enabled].toString(),
                row[BotApps.createdAt].toString(),
                row[BotApps.updatedAt].toString(),
            )
        }
}
