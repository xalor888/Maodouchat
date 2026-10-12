package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** 群接龙生命周期簇：创建、加入。 */

fun GroupChainRepository.createChain(chatId: String, creatorId: String, title: String, topic: String, maxEntries: Int): ChainDto? {
    if (!isValidId(chatId) || !isValidId(creatorId)) return null
    val t = title.trim()
    val tp = topic.trim()
    if (t.isBlank() || t.length > MAX_CHAIN_TITLE_LENGTH) return null
    if (tp.length > MAX_CHAIN_TOPIC_LENGTH) return null
    val cap = maxEntries.coerceIn(MIN_CHAIN_ENTRIES, MAX_CHAIN_ENTRIES)
    val id = "chain_" + UUID.randomUUID().toString().replace("-", "").take(16)
    return transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, creatorId)) return@transaction null
        GroupChains.insert {
            it[GroupChains.id] = id
            it[GroupChains.chatId] = chatId
            it[GroupChains.creatorId] = creatorId
            it[GroupChains.title] = t
            it[GroupChains.topic] = tp
            it[GroupChains.maxEntries] = cap
            it[GroupChains.active] = true
            it[GroupChains.createdAt] = System.currentTimeMillis()
        }
        getChainInTransaction(id, creatorId)
    }
}

fun GroupChainRepository.joinChain(chainId: String, userId: String, content: String): ChainDto? {
    if (!isValidId(chainId) || !isValidId(userId)) return null
    val c = content.trim()
    if (c.isBlank() || c.length > MAX_CHAIN_CONTENT_LENGTH) return null
    return transaction {
        // 8.50 修复 H1：先锁 chat 再锁 chain——原「先锁 chain 再锁 chat」与 leaveChat/
        // ConversationStateDeletion's chat -> chain lock order can otherwise form an AB-BA cycle.
        val chatId = GroupChains.select(GroupChains.chatId)
            .where { GroupChains.id eq chainId }
            .firstOrNull()?.get(GroupChains.chatId) ?: return@transaction null
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction null
        val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
        val closed = !chain[GroupChains.active] || (chain[GroupChains.closedAt] != null)
        if (closed) return@transaction null
        val already = GroupChainEntries.selectAll().where {
            (GroupChainEntries.chainId eq chainId) and (GroupChainEntries.userId eq userId)
        }.count() > 0
        if (already) return@transaction toChainDto(chain, userId)
        val currentCount = GroupChainEntries.selectAll().where { GroupChainEntries.chainId eq chainId }.count()
        val currentMaxSequence = GroupChainEntries.select(GroupChainEntries.sequence)
            .where { GroupChainEntries.chainId eq chainId }
            .orderBy(GroupChainEntries.sequence to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(GroupChainEntries.sequence) ?: 0
        // 满员不是「成功但未加入」：返回 null，路由按「接龙已结束或人数已满」回 400，
        // 否则客户端会把失败响应当成功刷新成未加入状态。
        if (currentCount >= chain[GroupChains.maxEntries]) return@transaction null

        val id = "ce_" + UUID.randomUUID().toString().replace("-", "").take(16)
        GroupChainEntries.insert {
            it[GroupChainEntries.id] = id
            it[GroupChainEntries.chainId] = chainId
            it[GroupChainEntries.userId] = userId
            it[GroupChainEntries.sequence] = currentMaxSequence + 1
            it[GroupChainEntries.content] = c
            it[GroupChainEntries.createdAt] = System.currentTimeMillis()
        }
        toChainDto(chain, userId)
    }
}

private fun GroupChainRepository.getChainInTransaction(chainId: String, viewerId: String): ChainDto? {
    val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.firstOrNull()
        ?: return null
    return toChainDto(chain, viewerId)
}
