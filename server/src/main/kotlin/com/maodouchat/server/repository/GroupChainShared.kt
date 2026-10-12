package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** 群接龙共享 helper（internal，供生命周期/查询簇使用）。 */

internal fun GroupChainRepository.toChainDto(
    chain: ResultRow,
    viewerId: String,
    preloadedEntries: List<ResultRow> = emptyList(),
    blocked: Set<String>? = null
): ChainDto? {
    val chainId = chain[GroupChains.id]
    val blocked = blocked ?: ConversationVisibility.blockedUserIdsInTx(viewerId)
    // 8.48：列表路径由调用方批量预取；单条路径（空）此处回查
    val entryRows = if (preloadedEntries.isNotEmpty()) preloadedEntries else
        GroupChainEntries.selectAll().where { GroupChainEntries.chainId eq chainId }
            .orderBy(GroupChainEntries.sequence to SortOrder.ASC)
            .toList()
    val entries = entryRows.filter { it[GroupChainEntries.userId] !in blocked }.map {
        ChainEntryDto(
            id = it[GroupChainEntries.id],
            userId = it[GroupChainEntries.userId],
            sequence = it[GroupChainEntries.sequence],
            content = it[GroupChainEntries.content],
            createdAt = it[GroupChainEntries.createdAt]
        )
    }
    return ChainDto(
        id = chainId,
        chatId = chain[GroupChains.chatId],
        creatorId = chain[GroupChains.creatorId],
        title = chain[GroupChains.title],
        topic = chain[GroupChains.topic],
        maxEntries = chain[GroupChains.maxEntries],
        active = chain[GroupChains.active] && chain[GroupChains.closedAt] == null,
        createdAt = chain[GroupChains.createdAt],
        closedAt = chain[GroupChains.closedAt],
        entryCount = entries.size,
        myJoined = entries.any { it.userId == viewerId },
        entries = entries
    )
}

internal fun GroupChainRepository.isMemberInTransaction(chatId: String, userId: String): Boolean =
    ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
    }.count() > 0

internal fun GroupChainRepository.isValidId(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
