package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** 群 PK 对决子域（B05：签到/接龙/PK 分独立子域，自 GroupCheckinRepository 拆出）。 */
class GroupPkRepository {

    @Serializable
    data class PkDto(
        val id: String,
        val chatId: String,
        val creatorId: String,
        val leftTitle: String,
        val rightTitle: String,
        val active: Boolean,
        val createdAt: Long,
        val closedAt: Long? = null,
        val leftCount: Int = 0,
        val rightCount: Int = 0,
        val totalVoters: Int = 0,
        val myChoice: String? = null
    )
    fun createPk(chatId: String, creatorId: String, leftTitle: String, rightTitle: String): PkDto? {
        if (!isValidId(chatId) || !isValidId(creatorId)) return null
        val lt = leftTitle.trim()
        val rt = rightTitle.trim()
        if (lt.isBlank() || rt.isBlank() || lt.length > MAX_PK_TITLE_LENGTH || rt.length > MAX_PK_TITLE_LENGTH) return null
        val id = "pk_" + UUID.randomUUID().toString().replace("-", "").take(16)
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, creatorId)) return@transaction null
            GroupPkRounds.insert {
                it[GroupPkRounds.id] = id
                it[GroupPkRounds.chatId] = chatId
                it[GroupPkRounds.creatorId] = creatorId
                it[GroupPkRounds.leftTitle] = lt
                it[GroupPkRounds.rightTitle] = rt
                it[GroupPkRounds.active] = true
                it[GroupPkRounds.createdAt] = System.currentTimeMillis()
            }
            getPkInTransaction(id, creatorId)
        }
    }

    fun listChatPks(chatId: String, viewerId: String, limit: Int = 30): List<PkDto> {
        if (!isValidId(chatId) || !isValidId(viewerId)) return emptyList()
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction emptyList()
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, viewerId)) return@transaction emptyList()
            val blocked = blockedUserIdsInTx(viewerId)
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

    fun getPk(pkId: String, viewerId: String): PkDto? {
        if (!isValidId(pkId) || !isValidId(viewerId)) return null
        return transaction {
            val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.firstOrNull()
                ?: return@transaction null
            if (!isMemberInTransaction(pk[GroupPkRounds.chatId], viewerId)) return@transaction null
            if (pk[GroupPkRounds.creatorId] in blockedUserIdsInTx(viewerId)) return@transaction null
            toPkDto(pk, viewerId)
        }
    }

    fun votePk(pkId: String, userId: String, choice: String): PkDto? {
        if (!isValidId(pkId) || !isValidId(userId)) return null
        val c = choice.trim().lowercase()
        if (c != "left" && c != "right") return null
        return try {
            votePkInTransaction(pkId, userId, c)
        } catch (error: Exception) {
            // 与签到双签同口径：并发首次投票可能同时 INSERT 撞 (pkId, userId) 主键，
            // PG 会 abort 事务。捕获必须在事务外，回滚后重试一次改票。
            if (!isUniqueViolation(error)) throw error
            votePkInTransaction(pkId, userId, c)
        }
    }

    private fun votePkInTransaction(pkId: String, userId: String, choice: String): PkDto? = transaction {
        // 8.50 修复 H1：先锁 chat 再锁 pk（原「先 pk 后 chat」与删除路径构成死锁环）
        val chatId = GroupPkRounds.select(GroupPkRounds.chatId)
            .where { GroupPkRounds.id eq pkId }
            .firstOrNull()?.get(GroupPkRounds.chatId) ?: return@transaction null
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction null
        val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
        if (!pk[GroupPkRounds.active] || pk[GroupPkRounds.closedAt] != null) return@transaction null
        val now = System.currentTimeMillis()
        val existing = GroupPkVotes.selectAll().where {
            (GroupPkVotes.pkId eq pkId) and (GroupPkVotes.userId eq userId)
        }.firstOrNull()
        if (existing != null) {
            GroupPkVotes.update({
                (GroupPkVotes.pkId eq pkId) and (GroupPkVotes.userId eq userId)
            }) {
                it[GroupPkVotes.choice] = choice
                it[GroupPkVotes.votedAt] = now
            }
        } else {
            GroupPkVotes.insert {
                it[GroupPkVotes.pkId] = pkId
                it[GroupPkVotes.userId] = userId
                it[GroupPkVotes.choice] = choice
                it[GroupPkVotes.votedAt] = now
            }
        }
        toPkDto(pk, userId)
    }

    fun closePk(pkId: String, userId: String): PkDto? {
        if (!isValidId(pkId) || !isValidId(userId)) return null
        return transaction {
            // 8.50 H1 同序：先锁 chat 再锁 pk，并校验创建者仍是群成员，
            // 否则退群/被移出后仍可关闭自己创建的 PK。
            val chatId = GroupPkRounds.select(GroupPkRounds.chatId)
                .where { GroupPkRounds.id eq pkId }
                .firstOrNull()
                ?.get(GroupPkRounds.chatId) ?: return@transaction null
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (!chat[Chats.isGroup] || !isMemberInTransaction(chatId, userId)) return@transaction null
            val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (pk[GroupPkRounds.creatorId] != userId) return@transaction null
            val closedAt = System.currentTimeMillis()
            GroupPkRounds.update({ GroupPkRounds.id eq pkId }) {
                it[active] = false
                it[GroupPkRounds.closedAt] = closedAt
            }
            // Exposed ResultRow 不会随 UPDATE 刷新。closePoll 用 forceClosed；这里重读行，
            // 避免关闭接口把 active=true / closedAt=null 回给客户端（像没关上）。
            val updated = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.first()
            toPkDto(updated, userId)
        }
    }

    private fun getPkInTransaction(pkId: String, viewerId: String): PkDto? {
        val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.firstOrNull()
            ?: return null
        return toPkDto(pk, viewerId)
    }

    private fun toPkDto(
        pk: ResultRow,
        viewerId: String,
        preloadedVotes: List<ResultRow> = emptyList(),
        blocked: Set<String>? = null
    ): PkDto? {
        val pkId = pk[GroupPkRounds.id]
        val blocked = blocked ?: blockedUserIdsInTx(viewerId)
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

    /** 9.152：唯一约束冲突检测。 */
    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
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
        private const val MAX_PK_TITLE_LENGTH = 120
    }
}
