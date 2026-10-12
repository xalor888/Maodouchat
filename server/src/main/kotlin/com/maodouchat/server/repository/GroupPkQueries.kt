package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 群 PK 查询簇：列表与单条读取。 */

fun GroupPkRepository.listChatPks(chatId: String, viewerId: String, limit: Int = 30): List<PkDto> {
    if (!isValidId(chatId) || !isValidId(viewerId)) return emptyList()
    return transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction emptyList()
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction emptyList()
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        val pkBase = GroupPkRounds.selectAll().where { GroupPkRounds.chatId eq chatId }
        val pkQuery = if (blocked.isEmpty()) pkBase
        else pkBase.andWhere { GroupPkRounds.creatorId notInList blocked.toList() }
        val pks = pkQuery
            .orderBy(GroupPkRounds.createdAt to SortOrder.DESC, GroupPkRounds.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .toList()
        // 8.48 修复 H3：批量取投票（此前 toPkDto 逐个 PK 全量载入 → N+1）
        val pkIds = pks.map { it[GroupPkRounds.id] }
        val votesByPk = if (pkIds.isEmpty()) emptyMap() else
            GroupPkVotes.selectAll()
                .where { GroupPkVotes.pkId inList pkIds }
                .toList()
                .groupBy { it[GroupPkVotes.pkId] }
        pks.mapNotNull {
            toPkDto(it, viewerId, votesByPk[it[GroupPkRounds.id]].orEmpty(), blocked)
        }
    }
}

fun GroupPkRepository.getPk(pkId: String, viewerId: String): PkDto? {
    if (!isValidId(pkId) || !isValidId(viewerId)) return null
    return transaction {
        val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.firstOrNull()
            ?: return@transaction null
        if (!isMemberInTransaction(pk[GroupPkRounds.chatId], viewerId)) return@transaction null
        if (pk[GroupPkRounds.creatorId] in ConversationVisibility.blockedUserIdsInTx(viewerId)) return@transaction null
        toPkDto(pk, viewerId)
    }
}
