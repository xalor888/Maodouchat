package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 1:1 直接会话配对簇：幂等创建、查找与映射修复。 */

fun ConversationCreationRepository.getOrCreateDirect(userId1: String, userId2: String): CreatedConversation {
    if (userId1 == userId2) reject(ConversationCreationFailure.DIRECT_SELF, userId1)
    val pairKey = pairKey(userId1, userId2)
    return try {
        transaction {
            validateParticipantsInTx(listOf(userId1, userId2)).throwIfInvalid()
            val existingId = lookupDirectIdInTx(pairKey, userId1, userId2)
            if (existingId != null) {
                ensureDirectMappingInTx(pairKey, existingId, userId1, userId2)
                return@transaction CreatedConversation(existingId)
            }
            createPairedConversationInTx(
                    pairKey = pairKey,
                    userId1 = userId1,
                    userId2 = userId2,
                    secret = false,
                )
        }
    } catch (error: Exception) {
        if (!isUniqueViolation(error)) throw error
        findDirect(userId1, userId2) ?: throw error
    }
}

/** Finds and repairs the unique mapping for an existing direct conversation. */
fun ConversationCreationRepository.findDirect(userId1: String, userId2: String): CreatedConversation? {
    if (userId1 == userId2) return null
    val pairKey = pairKey(userId1, userId2)
    val chatId = transaction {
        if (!hasActivePairInTx(userId1, userId2)) return@transaction null
        lookupDirectIdInTx(pairKey, userId1, userId2)
    } ?: return null
    ensureDirectMapping(pairKey, chatId, userId1, userId2)
    return CreatedConversation(chatId)
}

private fun ConversationCreationRepository.lookupDirectIdInTx(pairKey: String, userId1: String, userId2: String): String? {
    val mapped = DirectChatPairs.selectAll()
        .where { DirectChatPairs.pairKey eq pairKey }
        .firstOrNull()
        ?.get(DirectChatPairs.chatId)
    if (mapped != null) {
        if (isIntactPairInTx(mapped, userId1, userId2, secret = false)) return mapped
        DirectChatPairs.deleteWhere { DirectChatPairs.pairKey eq pairKey }
    }
    return null
}

private fun ConversationCreationRepository.ensureDirectMapping(pairKey: String, chatId: String, userId1: String, userId2: String) {
    try {
        transaction {
            if (!hasActivePairInTx(userId1, userId2)) return@transaction
            ensureDirectMappingInTx(pairKey, chatId, userId1, userId2)
        }
    } catch (error: Exception) {
        if (!isUniqueViolation(error)) throw error
    }
}

private fun ConversationCreationRepository.ensureDirectMappingInTx(
    pairKey: String,
    chatId: String,
    userId1: String,
    userId2: String,
) {
    Chats.select(Chats.id).where { Chats.id eq chatId }.forUpdate().firstOrNull() ?: return
    if (!isIntactPairInTx(chatId, userId1, userId2, secret = false)) return
    if (DirectChatPairs.selectAll().where { DirectChatPairs.pairKey eq pairKey }.any()) return
    DirectChatPairs.insert {
        it[DirectChatPairs.pairKey] = pairKey
        it[DirectChatPairs.chatId] = chatId
        it[createdAt] = System.currentTimeMillis()
    }
}
