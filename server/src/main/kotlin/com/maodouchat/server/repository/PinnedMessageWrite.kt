package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 置顶消息变更（置顶/取消/清空）。

/**
 * @param actorIsManager 群聊时由路由传入 isOwnerOrAdmin；单聊恒 true
 */
fun PinnedMessageRepository.toggle(
    chatId: String,
    messageId: String,
    actorId: String,
    actorIsManager: Boolean,
    requireBotDeliverable: Boolean = false
): PinnedMessageRepository.ToggleOutcome {
    // PG：唯一冲突后当前事务 abort，同事务任何后续查询都抛 25P02（500）；
    // 且嵌套 transaction{} 复用外层连接救不回来——必须 catch 在事务外、回滚后开新事务回读。
    return try {
        transaction {
            if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
                return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.FORBIDDEN)
            }
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                ?: return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.NOT_FOUND)
            val isGroup = chat[Chats.isGroup]
            if (isGroup && !actorIsManager) {
                return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.FORBIDDEN)
            }
            val isMember = ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId) }
                .firstOrNull() != null
            if (!isMember) return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.FORBIDDEN)

            val msg = MessagingV2Messages.selectAll()
                .where { MessagingV2Messages.id eq messageId }
                .firstOrNull()
                ?: return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.NOT_FOUND)
            if (msg[MessagingV2Messages.conversationId] != chatId) {
                return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.NOT_FOUND)
            }
            if (msg[MessagingV2Messages.recordClass] != MessagingV2RecordClass.MESSAGE) {
                // 允许取消历史脏数据
                val existingDirty = PinnedMessages.selectAll()
                    .where { (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId) }
                    .firstOrNull()
                if (existingDirty != null) {
                    PinnedMessages.deleteWhere {
                        (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId)
                    }
                    return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.UNPINNED, listInTx(chatId))
                }
                return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.NOT_PINNABLE)
            }

            val existing = PinnedMessages.selectAll()
                .where { (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId) }
                .forUpdate()
                .firstOrNull()
            if (existing != null) {
                PinnedMessages.deleteWhere {
                    (PinnedMessages.chatId eq chatId) and (PinnedMessages.messageId eq messageId)
                }
                return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.UNPINNED, listInTx(chatId))
            }

            val count = PinnedMessages.selectAll()
                .where { PinnedMessages.chatId eq chatId }
                .forUpdate()
                .toList()
                .size
            if (count >= PinnedMessageRepository.MAX_PINS_PER_CHAT) {
                return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.LIMIT, listInTx(chatId))
            }

            // 并发 toggle 竞态：两条 unpinned→pin 同时进入，forUpdate 对不存在的行无效，
            // 后到者会因 (chatId, messageId) 唯一约束抛异常；由外层 catch 在回滚后的新事务回读。
            PinnedMessages.insert {
                it[PinnedMessages.chatId] = chatId
                it[PinnedMessages.messageId] = messageId
                it[PinnedMessages.pinnedBy] = actorId
                it[PinnedMessages.pinnedAt] = System.currentTimeMillis()
            }
            PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.PINNED, listInTx(chatId))
        }
    } catch (e: Exception) {
        if (isUniqueViolation(e)) {
            // 本事务已由 Exposed 回滚并归还连接；新事务读取胜者已提交的置顶列表
            transaction { PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.PINNED, listInTx(chatId)) }
        } else throw e
    }
}


/**
 * Clear all pins for a chat (bot/admin). Returns remaining list (empty on success).
 */
fun PinnedMessageRepository.clearAll(
    chatId: String,
    actorId: String,
    actorIsManager: Boolean,
    requireBotDeliverable: Boolean = false
): PinnedMessageRepository.ToggleOutcome {
    return transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
            return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.FORBIDDEN)
        }
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.NOT_FOUND)
        val isGroup = chat[Chats.isGroup]
        if (isGroup && !actorIsManager) {
            return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.FORBIDDEN)
        }
        val isMember = ChatParticipants.selectAll()
            .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId) }
            .firstOrNull() != null
        if (!isMember) return@transaction PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.FORBIDDEN)
        PinnedMessages.deleteWhere { PinnedMessages.chatId eq chatId }
        PinnedMessageRepository.ToggleOutcome(PinnedMessageRepository.PinResult.UNPINNED, emptyList())
    }
}
