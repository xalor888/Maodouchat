package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 群接龙查询簇：列表与单条读取。 */

fun GroupChainRepository.listChains(chatId: String, viewerId: String, limit: Int = 30): List<ChainDto> {
    if (!isValidId(chatId) || !isValidId(viewerId)) return emptyList()
    return transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction emptyList()
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction emptyList()
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        val chainBase = GroupChains.selectAll().where { GroupChains.chatId eq chatId }
        val chainQuery = if (blocked.isEmpty()) chainBase
        else chainBase.andWhere { GroupChains.creatorId notInList blocked.toList() }
        val chains = chainQuery
            .orderBy(GroupChains.createdAt to SortOrder.DESC, GroupChains.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .toList()
        // 8.48 修复 H2：批量取全部条目（此前 toChainDto 逐条查 → N+1）
        val chainIds = chains.map { it[GroupChains.id] }
        val entriesByChain = if (chainIds.isEmpty()) emptyMap() else
            GroupChainEntries.selectAll()
                .where { GroupChainEntries.chainId inList chainIds }
                .orderBy(GroupChainEntries.sequence to SortOrder.ASC)
                .toList()
                .groupBy { it[GroupChainEntries.chainId] }
        chains.mapNotNull {
            toChainDto(it, viewerId, entriesByChain[it[GroupChains.id]].orEmpty(), blocked)
        }
    }
}

fun GroupChainRepository.getChain(chainId: String, viewerId: String): ChainDto? {
    if (!isValidId(chainId) || !isValidId(viewerId)) return null
    return transaction {
        val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.firstOrNull()
            ?: return@transaction null
        if (!isMemberInTransaction(chain[GroupChains.chatId], viewerId)) return@transaction null
        if (chain[GroupChains.creatorId] in ConversationVisibility.blockedUserIdsInTx(viewerId)) return@transaction null
        toChainDto(chain, viewerId)
    }
}
