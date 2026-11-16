package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message

// 批量删除判定，逐字平移自 app 侧 ChatDetailBatchDeleteDialog。
object MessageBatchDeletePolicy {

    // 服务端 mutation 限流 60/min：批量封顶 60 条，超出提示分批，避免 429「删一半剩一半」
    const val BATCH_CAP = 60

    fun resolve(selectedMessages: List<Message>, currentUserId: String): MessageBatchDelete {
        // 与单条删除语义一致：仅删除本人消息（服务端 403 只能删自己发送的消息）
        val deletableBatch = selectedMessages.filter { it.senderId == currentUserId }
        val cappedBatch = deletableBatch.take(BATCH_CAP)
        return MessageBatchDelete(
            cappedIds = cappedBatch.map { it.id },
            cappedCount = cappedBatch.size,
            cappedOut = deletableBatch.size - cappedBatch.size,
            skippedCount = selectedMessages.size - deletableBatch.size,
            canConfirm = cappedBatch.isNotEmpty(),
        )
    }
}

data class MessageBatchDelete(
    val cappedIds: List<String>,
    val cappedCount: Int,
    val cappedOut: Int,
    val skippedCount: Int,
    val canConfirm: Boolean,
)
