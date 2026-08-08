package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.SecretChatDao
import com.maodouchat.data.local.entity.SecretChatEntity
import kotlinx.coroutines.flow.Flow

class SecretChatRepository(private val dao: SecretChatDao) {

    suspend fun isSecret(chatId: String): Boolean {
        if (chatId.isBlank()) return false
        return dao.isSecret(chatId)
    }

    suspend fun enable(chatId: String) {
        if (chatId.isBlank()) return
        dao.upsert(SecretChatEntity(chatId = chatId, enabledAt = System.currentTimeMillis()))
    }

    suspend fun disable(chatId: String) {
        if (chatId.isBlank()) return
        dao.remove(chatId)
    }

    suspend fun listSecretChatIds(): List<String> = dao.listSecretChatIds()

    fun observeSecretChatIds(): Flow<List<String>> = dao.observeSecretChatIds()

    suspend fun remove(chatId: String) = dao.remove(chatId)
}
