package com.maodouchat.data.local

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import com.maodouchat.conversation.ReadReceiptSource
import kotlinx.coroutines.flow.Flow

/**
 * 把 `MessagingV2Dao` 适配成 `ui/` 的 `ReadReceiptSource` 端口（G64）。
 *
 * **为什么必须放在 data 层**：这是全项目唯一同时认识 DAO 与 UI 端口的地方。
 * 放进 `ui/` 会让 `ui/` 重新 import `data.local.dao`，`ClientArchitectureTest` 的
 * ui→DAO 棘轮当场就红；放进 `ChatDetailViewModel.kt` 则会让那个 3131 行的热点文件继续变胖
 * （行数棘轮同样会红）。放在 data 层是唯一同时满足两条边界的位置。
 *
 * 它只暴露两个**读**操作，不含任何写能力——端口收得越窄，「UI 意外拿到写能力」的面越小。
 */
internal class RoomReadReceiptSource(private val dao: MessagingV2Dao) : ReadReceiptSource {
    override suspend fun getReceiptsForMessage(
        ownerUserId: String,
        messageId: String,
    ): List<MessagingV2ReceiptEntity> = dao.getReceiptsForMessage(ownerUserId, messageId)

    override fun observeReceiptsForConversation(
        ownerUserId: String,
        conversationId: String,
    ): Flow<List<MessagingV2ReceiptEntity>> = dao.observeReceiptsForConversation(ownerUserId, conversationId)
}
