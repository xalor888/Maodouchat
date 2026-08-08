package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.AiOperationDao
import com.maodouchat.data.local.entity.AiOperationEntity
import kotlinx.coroutines.flow.Flow

class AiOperationRepository(private val dao: AiOperationDao) {
    fun observeActionable(ownerUserId: String, chatId: String): Flow<List<AiOperationEntity>> =
        dao.observeActionable(ownerUserId, chatId)

    suspend fun enqueue(operation: AiOperationEntity) = dao.upsert(operation)

    suspend fun get(operationId: String): AiOperationEntity? = dao.get(operationId)

    suspend fun getRunning(ownerUserId: String, chatId: String): AiOperationEntity? =
        dao.getRunning(ownerUserId, chatId)

    suspend fun getNextQueued(ownerUserId: String, chatId: String): AiOperationEntity? =
        dao.getNextQueued(ownerUserId, chatId)

    suspend fun markQueued(operationId: String): Boolean =
        dao.markQueued(operationId, System.currentTimeMillis()) == 1

    suspend fun markRunning(operationId: String): Boolean =
        dao.markRunning(operationId, System.currentTimeMillis()) == 1

    suspend fun markSucceeded(operationId: String): Boolean =
        dao.markSucceeded(operationId, System.currentTimeMillis()) == 1

    suspend fun markFailed(operationId: String, errorCode: String): Boolean =
        dao.markFailed(operationId, errorCode.take(80), System.currentTimeMillis()) == 1

    suspend fun markCancelled(operationId: String): Boolean =
        dao.markCancelled(operationId, System.currentTimeMillis()) == 1

    suspend fun recoverInterrupted(ownerUserId: String, chatId: String): Int =
        dao.recoverInterrupted(ownerUserId, chatId, System.currentTimeMillis())

    suspend fun dismiss(operationId: String) = dao.delete(operationId)

    suspend fun deleteByChatId(chatId: String) = dao.deleteByChatId(chatId)

    suspend fun pruneTerminal(retentionMs: Long = 7L * 24L * 60L * 60L * 1_000L): Int =
        dao.pruneTerminal(System.currentTimeMillis() - retentionMs)
}
