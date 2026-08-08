package com.maodouchat.data.repository

import androidx.room.withTransaction
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.Message

/** Atomically accepts a running AI operation and persists its message metadata result. */
class AiMessageResultStore(private val database: AppDatabase) {
    suspend fun commit(operationId: String?, message: Message): Boolean {
        val accepted = database.withTransaction {
            if (operationId != null) {
                val ok = database.aiOperationDao()
                    .markSucceeded(operationId, System.currentTimeMillis()) == 1
                if (!ok) return@withTransaction null
            }
            val existing = database.messageDao().getMessageById(message.id)?.toDomain()
            val merged = existing?.let { current ->
                mergeMessageForPersistence(current, message).copy(
                    starred = current.starred,
                    reactions = current.reactions
                )
            } ?: message
            database.messageDao().insertMessage(merged.toEntity())
            merged
        }
        if (accepted != null) {
            // Voice transcript / translation / analysis must enter keyword search immediately.
            try {
                MessageSearchRepository(database).indexMessage(accepted)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // Index is best-effort after durable Room write.
            }
        }
        return accepted != null
    }
}
