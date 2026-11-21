package com.maodouchat.crypto

import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.domain.messaging.SenderKeyRetryTask
import com.maodouchat.domain.messaging.SenderKeyRetryTaskStore

class SenderKeyRetryTaskStoreAdapter(
    private val retryDao: SenderKeyRetryDao
) : SenderKeyRetryTaskStore {
    override suspend fun get(ownerUserId: String, chatId: String): SenderKeyRetryTask? =
        retryDao.get(ownerUserId, chatId)?.toRetryTask()

    override suspend fun upsert(task: SenderKeyRetryTask) {
        retryDao.upsert(task.toEntity())
    }
}

fun SenderKeyRetryEntity.toRetryTask(): SenderKeyRetryTask =
    SenderKeyRetryTask(
        ownerUserId = ownerUserId,
        chatId = chatId,
        epoch = epoch,
        reason = reason,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        updatedAt = updatedAt
    )

fun SenderKeyRetryTask.toEntity(): SenderKeyRetryEntity =
    SenderKeyRetryEntity(
        ownerUserId = ownerUserId,
        chatId = chatId,
        epoch = epoch,
        reason = reason,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        updatedAt = updatedAt
    )
