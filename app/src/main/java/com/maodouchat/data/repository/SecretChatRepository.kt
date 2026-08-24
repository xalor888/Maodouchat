package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.SecretChatDao
import com.maodouchat.data.local.entity.SecretChatEntity

/** 密聊本机 TTL 心跳。是否密聊走 ChatDao / chatType=SECRET。 */
class SecretChatRepository(private val dao: SecretChatDao) {

    suspend fun touch(chatId: String) {
        if (chatId.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = dao.get(chatId)
        if (existing == null) {
            dao.upsert(SecretChatEntity(chatId = chatId, enabledAt = now, lastActivityAt = now))
        } else {
            dao.touchActivity(chatId, now)
        }
    }

    suspend fun get(chatId: String): SecretChatEntity? =
        if (chatId.isBlank()) null else dao.get(chatId)

    suspend fun listActivity() = dao.listActivity()

    suspend fun remove(chatId: String) {
        if (chatId.isBlank()) return
        dao.remove(chatId)
    }
}
