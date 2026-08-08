package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.model.PinnedMessageResponse
import java.sql.SQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 会话级消息置顶：群仅 OWNER/ADMIN；单聊双方均可；最多 [MAX_PINS_PER_CHAT] 条。
 * 仅存 messageId 元数据，不复制密文。
 */
class PinnedMessageRepository {

    enum class PinResult {
        PINNED,
        UNPINNED,
        NOT_FOUND,
        FORBIDDEN,
        LIMIT,
        NOT_PINNABLE
    }

    data class ToggleOutcome(
        val result: PinResult,
        val pins: List<PinnedMessageResponse> = emptyList()
    )

    fun list(chatId: String): List<PinnedMessageResponse> {
        return transaction {
            PinnedMessages.selectAll()
                .where { PinnedMessages.chatId eq chatId }
                .orderBy(PinnedMessages.pinnedAt to SortOrder.DESC)
                .map { row ->
                    PinnedMessageResponse(
                        chatId = row[PinnedMessages.chatId],
                        messageId = row[PinnedMessages.messageId],
                        pinnedBy = row[PinnedMessages.pinnedBy],
                        pinnedAt = row[PinnedMessages.pinnedAt]
                    )
                }
        }
    }

    /**
     * @param actorIsManager 群聊时由路由传入 isOwnerOrAdmin；单聊恒 true
     */
    fun toggle(
        chatId: String,
        messageId: String,
        actorId: String,
        actorIsManager: Boolean
    ): ToggleOutcome {
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction ToggleOutcome(PinResult.NOT_FOUND)
            val isGroup = chat[Chats.isGroup]
            if (isGroup && !actorIsManager) {
                return@transaction ToggleOutcome(PinResult.FORBIDDEN)
            }
            val isMember = ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId) }
                .firstOrNull() != null
            if (!isMember) return@transaction ToggleOutcome(PinResult.FORBIDDEN)

            val msg = Messages.selectAll().where { Messages.id eq messageId }.firstOrNull()
                ?: return@transaction ToggleOutcome(PinResult.NOT_FOUND)
            if (msg[Messages.chatId] != chatId) {
                return@transaction ToggleOutcome(PinResult.NOT_FOUND)
            }
            val type = msg[Messages.type]
            if (type in NON_PINNABLE_TYPES) {
                // 允许取消历史脏数据
                val existingDirty = PinnedMessages.selectAll()
                    .where { (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId) }
                    .firstOrNull()
                if (existingDirty != null) {
                    PinnedMessages.deleteWhere {
                        (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId)
                    }
                    return@transaction ToggleOutcome(PinResult.UNPINNED, listInTx(chatId))
                }
                return@transaction ToggleOutcome(PinResult.NOT_PINNABLE)
            }

            val existing = PinnedMessages.selectAll()
                .where { (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId) }
                .forUpdate()
                .firstOrNull()
            if (existing != null) {
                PinnedMessages.deleteWhere {
                    (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId)
                }
                return@transaction ToggleOutcome(PinResult.UNPINNED, listInTx(chatId))
            }

            val count = PinnedMessages.selectAll()
                .where { PinnedMessages.chatId eq chatId }
                .forUpdate()
                .count()
            if (count >= MAX_PINS_PER_CHAT) {
                return@transaction ToggleOutcome(PinResult.LIMIT, listInTx(chatId))
            }

            // 并发 toggle 竞态：两条 unpinned→pin 同时进入，forUpdate 对不存在的行无效，
            // 后到者会因 (chatId, messageId) 唯一约束抛异常；捕获并视为已置顶，避免 500。
            return@transaction try {
                PinnedMessages.insert {
                    it[PinnedMessages.chatId] = chatId
                    it[PinnedMessages.messageId] = messageId
                    it[PinnedMessages.pinnedBy] = actorId
                    it[PinnedMessages.pinnedAt] = System.currentTimeMillis()
                }
                ToggleOutcome(PinResult.PINNED, listInTx(chatId))
            } catch (e: Exception) {
                if (isUniqueViolation(e)) ToggleOutcome(PinResult.PINNED, listInTx(chatId)) else throw e
            }
        }
    }

    /**
     * Clear all pins for a chat (bot/admin). Returns remaining list (empty on success).
     */
    fun clearAll(chatId: String, actorId: String, actorIsManager: Boolean): ToggleOutcome {
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction ToggleOutcome(PinResult.NOT_FOUND)
            val isGroup = chat[Chats.isGroup]
            if (isGroup && !actorIsManager) {
                return@transaction ToggleOutcome(PinResult.FORBIDDEN)
            }
            val isMember = ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId) }
                .firstOrNull() != null
            if (!isMember) return@transaction ToggleOutcome(PinResult.FORBIDDEN)
            PinnedMessages.deleteWhere { PinnedMessages.chatId eq chatId }
            ToggleOutcome(PinResult.UNPINNED, emptyList())
        }
    }

    private fun listInTx(chatId: String): List<PinnedMessageResponse> {
        return PinnedMessages.selectAll()
            .where { PinnedMessages.chatId eq chatId }
            .orderBy(PinnedMessages.pinnedAt to SortOrder.DESC)
            .map { row ->
                PinnedMessageResponse(
                    chatId = row[PinnedMessages.chatId],
                    messageId = row[PinnedMessages.messageId],
                    pinnedBy = row[PinnedMessages.pinnedBy],
                    pinnedAt = row[PinnedMessages.pinnedAt]
                )
            }
    }

    companion object {
        const val MAX_PINS_PER_CHAT = 20
        private val NON_PINNABLE_TYPES = setOf("SK_DIST", "SYSTEM", "REVOKED", "NUDGE")

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
}
