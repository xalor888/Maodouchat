package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** 群接龙子域（B05：签到/接龙/PK 分独立子域，自 GroupCheckinRepository 拆出）。 */
class GroupChainRepository {

    @Serializable
    data class ChainEntryDto(
        val id: String,
        val userId: String,
        val sequence: Int,
        val content: String,
        val createdAt: Long
    )

    @Serializable
    data class ChainDto(
        val id: String,
        val chatId: String,
        val creatorId: String,
        val title: String,
        val topic: String,
        val maxEntries: Int,
        val active: Boolean,
        val createdAt: Long,
        val closedAt: Long? = null,
        val entryCount: Int = 0,
        val myJoined: Boolean = false,
        val entries: List<ChainEntryDto> = emptyList()
    )
    fun createChain(chatId: String, creatorId: String, title: String, topic: String, maxEntries: Int): ChainDto? {
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

    fun listChains(chatId: String, viewerId: String, limit: Int = 30): List<ChainDto> {
        if (!isValidId(chatId) || !isValidId(viewerId)) return emptyList()
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction emptyList()
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction emptyList()
            val blocked = blockedUserIdsInTx(viewerId)
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

    fun getChain(chainId: String, viewerId: String): ChainDto? {
        if (!isValidId(chainId) || !isValidId(viewerId)) return null
        return transaction {
            val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.firstOrNull()
                ?: return@transaction null
            if (!isMemberInTransaction(chain[GroupChains.chatId], viewerId)) return@transaction null
            if (chain[GroupChains.creatorId] in blockedUserIdsInTx(viewerId)) return@transaction null
            toChainDto(chain, viewerId)
        }
    }

    fun joinChain(chainId: String, userId: String, content: String): ChainDto? {
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

    private fun getChainInTransaction(chainId: String, viewerId: String): ChainDto? {
        val chain = GroupChains.selectAll().where { GroupChains.id eq chainId }.firstOrNull()
            ?: return null
        return toChainDto(chain, viewerId)
    }

    private fun toChainDto(
        chain: ResultRow,
        viewerId: String,
        preloadedEntries: List<ResultRow> = emptyList(),
        blocked: Set<String>? = null
    ): ChainDto? {
        val chainId = chain[GroupChains.id]
        val blocked = blocked ?: blockedUserIdsInTx(viewerId)
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

    private fun blockedUserIdsInTx(viewerId: String?): Set<String> {
        if (viewerId.isNullOrBlank()) return emptySet()
        return BlockedUsers.selectAll()
            .where {
                (BlockedUsers.blockerId eq viewerId) or (BlockedUsers.blockedId eq viewerId)
            }
            .map { row ->
                if (row[BlockedUsers.blockerId] == viewerId) row[BlockedUsers.blockedId]
                else row[BlockedUsers.blockerId]
            }
            .toSet()
    }

    private fun isMemberInTransaction(chatId: String, userId: String): Boolean =
        ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }.count() > 0

    private fun isValidId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    companion object {
        private const val MIN_CHAIN_ENTRIES = 2
        private const val MAX_CHAIN_ENTRIES = 1_000
        private const val MAX_CHAIN_TITLE_LENGTH = 200
        private const val MAX_CHAIN_TOPIC_LENGTH = 500
        private const val MAX_CHAIN_CONTENT_LENGTH = 500
    }
}
