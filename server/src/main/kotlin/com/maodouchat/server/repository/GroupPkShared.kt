package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** 群 PK 共享 helper（internal，供生命周期/查询簇使用）。 */

internal fun GroupPkRepository.toPkDto(
    pk: ResultRow,
    viewerId: String,
    preloadedVotes: List<ResultRow> = emptyList(),
    blocked: Set<String>? = null
): PkDto? {
    val pkId = pk[GroupPkRounds.id]
    val blocked = blocked ?: ConversationVisibility.blockedUserIdsInTx(viewerId)
    // 8.48：列表路径由调用方批量预取；单条路径（空）此处回查
    val votes = (if (preloadedVotes.isNotEmpty()) preloadedVotes else
        GroupPkVotes.selectAll().where { GroupPkVotes.pkId eq pkId }.toList()
        ).filter { it[GroupPkVotes.userId] !in blocked }
    var left = 0
    var right = 0
    var myChoice: String? = null
    for (v in votes) {
        if (v[GroupPkVotes.choice] == "left") left++ else right++
        if (v[GroupPkVotes.userId] == viewerId) myChoice = v[GroupPkVotes.choice]
    }
    return PkDto(
        id = pkId,
        chatId = pk[GroupPkRounds.chatId],
        creatorId = pk[GroupPkRounds.creatorId],
        leftTitle = pk[GroupPkRounds.leftTitle],
        rightTitle = pk[GroupPkRounds.rightTitle],
        active = pk[GroupPkRounds.active] && pk[GroupPkRounds.closedAt] == null,
        createdAt = pk[GroupPkRounds.createdAt],
        closedAt = pk[GroupPkRounds.closedAt],
        leftCount = left,
        rightCount = right,
        totalVoters = votes.size,
        myChoice = myChoice
    )
}

internal fun GroupPkRepository.isMemberInTransaction(chatId: String, userId: String): Boolean =
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0

internal fun GroupPkRepository.isValidId(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
