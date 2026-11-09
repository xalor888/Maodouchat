package com.maodouchat.core.model

import kotlinx.coroutines.flow.Flow

/**
 * 已读回执的读取端口（G64）。
 *
 * `ui/` 层只认识这个抽象，不直连 DAO；持久化仍走 app 侧 Room 实体，
 * 边界映射在 app 侧的 RoomReadReceiptSource 内做。
 */
interface ReadReceiptSource {
    suspend fun getReceiptsForMessage(ownerUserId: String, messageId: String): List<MessageReceipt>

    fun observeReceiptsForConversation(ownerUserId: String, conversationId: String): Flow<List<MessageReceipt>>
}
