package com.maodouchat.server.repository

import com.maodouchat.server.db.SecretChatPairs
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 密聊配对簇：幂等创建与查找。 */

fun ConversationCreationRepository.getOrCreateSecret(userId1: String, userId2: String): CreatedConversation {
    if (userId1 == userId2) reject(ConversationCreationFailure.DIRECT_SELF, userId1)
    val pairKey = pairKey(userId1, userId2)
    return try {
        transaction {
            validateParticipantsInTx(listOf(userId1, userId2)).throwIfInvalid()
            lookupSecretIdInTx(pairKey, userId1, userId2)?.let(::CreatedConversation)
                ?: createPairedConversationInTx(
                    pairKey = pairKey,
                    userId1 = userId1,
                    userId2 = userId2,
                    secret = true,
                )
        }
    } catch (error: Exception) {
        if (!isUniqueViolation(error)) throw error
        findSecret(userId1, userId2) ?: throw error
    }
}

fun ConversationCreationRepository.findSecret(userId1: String, userId2: String): CreatedConversation? {
    if (userId1 == userId2) return null
    val pairKey = pairKey(userId1, userId2)
    return transaction {
        if (!hasActivePairInTx(userId1, userId2)) return@transaction null
        lookupSecretIdInTx(pairKey, userId1, userId2)?.let(::CreatedConversation)
    }
}

private fun ConversationCreationRepository.lookupSecretIdInTx(pairKey: String, userId1: String, userId2: String): String? {
    val mapped = SecretChatPairs.selectAll()
        .where { SecretChatPairs.pairKey eq pairKey }
        .firstOrNull()
        ?.get(SecretChatPairs.chatId)
    if (mapped == null) return null
    if (isIntactPairInTx(mapped, userId1, userId2, secret = true)) return mapped
    SecretChatPairs.deleteWhere { SecretChatPairs.pairKey eq pairKey }
    return null
}
