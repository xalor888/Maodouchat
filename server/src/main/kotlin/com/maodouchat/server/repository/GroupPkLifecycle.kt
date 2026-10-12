package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** 群 PK 生命周期簇：创建、投票、关闭。 */

fun GroupPkRepository.createPk(chatId: String, creatorId: String, leftTitle: String, rightTitle: String): PkDto? {
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

fun GroupPkRepository.votePk(pkId: String, userId: String, choice: String): PkDto? {
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

private fun GroupPkRepository.votePkInTransaction(pkId: String, userId: String, choice: String): PkDto? = transaction {
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

fun GroupPkRepository.closePk(pkId: String, userId: String): PkDto? {
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

private fun GroupPkRepository.getPkInTransaction(pkId: String, viewerId: String): PkDto? {
    val pk = GroupPkRounds.selectAll().where { GroupPkRounds.id eq pkId }.firstOrNull()
        ?: return null
    return toPkDto(pk, viewerId)
}
