package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.model.MessageResponse
import java.sql.SQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction

class StarMessageRepository {

    /**
     * 切换星标状态：已星标则取消，未星标则添加。
     * @return true = 现在是星标；false = 现在不是星标；null = 消息类型不可星标
     */
    fun toggleStar(userId: String, messageId: String): Boolean? = try {
        transaction {
            // MessageRepository 的消息变更先锁 message 再锁 chat；星标保持同序。
            val message = Messages.selectAll().where { Messages.id eq messageId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val chatId = message[Messages.chatId]
            Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val isMember = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }.firstOrNull() != null
            if (!isMember) return@transaction null
            val msgType = message[Messages.type]
            val expiresAt = message[Messages.expiresAt]
            val expired = expiresAt != null && expiresAt > 0L && expiresAt <= System.currentTimeMillis()
            // 内部消息、撤回墓碑和已过期消息均不可星标。
            if (msgType in NON_STARRABLE_TYPES || expired) {
                // 若历史数据已星标，允许取消
                val existing = StarMessages.selectAll()
                    .where { (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId) }
                    .firstOrNull()
                if (existing != null) {
                    StarMessages.deleteWhere { (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId) }
                    return@transaction false
                }
                return@transaction null
            }
            val existing = StarMessages.selectAll()
                .where { (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId) }
                .forUpdate()
                .firstOrNull()
            if (existing != null) {
                StarMessages.deleteWhere { (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId) }
                false
            } else {
                // 并发 toggle 竞态：两条 unpinned→star 同时进入，forUpdate 对不存在的行无效，
                // 后到者撞 (userId, messageId) 唯一约束——异常交给事务外 catch 处理（9.139）
                StarMessages.insert {
                    it[StarMessages.userId] = userId
                    it[StarMessages.messageId] = messageId
                    it[StarMessages.starredAt] = System.currentTimeMillis()
                }
                true
            }
        }
    } catch (error: Exception) {
        // 9.139：PG 上唯一冲突会 abort 整事务——捕获必须在事务外（此前事务内 catch 后
        // COMMIT 抛 25P02 逃逸为 500；H2 测试不暴露该问题）。回滚后新事务幂等回读当前状态。
        if (!isUniqueViolation(error)) throw error
        transaction {
            StarMessages.selectAll()
                .where { (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId) }
                .firstOrNull() != null
        }
    }

    companion object {
        /** 星标列表返回上限（约束 O(n) join，客户端搜索已覆盖全量语义）。 */
        const val MAX_STARRED_RETURN = 1000
        private val NON_STARRABLE_TYPES = setOf("SK_DIST", "SYSTEM", "REVOKED")

        private fun isUniqueViolation(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                val message = current.message.orEmpty().lowercase()
                if (current is SQLException && current.sqlState == "23505") return true
                if (message.contains("unique") || message.contains("duplicate key")) return true
                current = current.cause
            }
            return false
        }
    }

    /**
     * 获取用户的星标消息列表
     * @param chatId 可选，按聊天筛选
     */
    fun getStarredMessages(userId: String, chatId: String? = null): List<MessageResponse> {
        return transaction {
            // 8.38：星标数量随时间无限增长——取最近 1000 条约束 join/序列化规模
            //（对超量用户展示最近 1000 条，客户端可搜索/翻页已足够）
            val starredIds = StarMessages.selectAll()
                .where { StarMessages.userId eq userId }
                .orderBy(StarMessages.starredAt to SortOrder.DESC)
                .limit(MAX_STARRED_RETURN)
                .map { it[StarMessages.messageId] }
            if (starredIds.isEmpty()) return@transaction emptyList()

            // 与历史消息一致：双向拉黑过滤（此前只过滤「我拉黑的」，漏掉「拉黑我的」——
            // 被拉黑方不得再读到拉黑方的星标密文/元数据）
            val blockedByMe = BlockedUsers.selectAll()
                .where { BlockedUsers.blockerId eq userId }
                .map { it[BlockedUsers.blockedId] }
                .toSet()
            val blockedMe = BlockedUsers.selectAll()
                .where { BlockedUsers.blockedId eq userId }
                .map { it[BlockedUsers.blockerId] }
                .toSet()
            val blockedSenders = blockedByMe + blockedMe

            // 仅返回用户仍是成员的会话消息，避免退群/被踢后继续拉取密文
            val membershipJoin = Messages.innerJoin(
                ChatParticipants,
                { Messages.chatId },
                { ChatParticipants.chatId }
            )
            val now = System.currentTimeMillis()
            val notExpired = Messages.expiresAt.isNull() or
                (Messages.expiresAt eq 0L) or
                (Messages.expiresAt greater now)
            val base = (Messages.id inList starredIds) and
                (ChatParticipants.userId eq userId) and
                (Messages.type notInList NON_STARRABLE_TYPES.toList()) and
                notExpired and
                if (chatId != null) (Messages.chatId eq chatId) else Op.TRUE
            val condition = if (blockedSenders.isEmpty()) base
            else base and (Messages.senderId notInList blockedSenders.toList())
            val messages = membershipJoin.selectAll()
                .where { condition }
                .map { row ->
                    MessageResponse(
                        id = row[Messages.id],
                        chatId = row[Messages.chatId],
                        senderId = row[Messages.senderId],
                        content = row[Messages.content],
                        type = row[Messages.type],
                        timestamp = row[Messages.timestamp],
                        status = row[Messages.status],
                        editedAt = row[Messages.editedAt],
                        starred = true,
                        expiresAt = row[Messages.expiresAt]?.takeIf { it > 0L }
                    )
                }

            // 保持按 starredAt 排序
            val idOrder = starredIds.withIndex().associate { (i, id) -> id to i }
            messages.sortedBy { idOrder[it.id] ?: Int.MAX_VALUE }
        }
    }
}
