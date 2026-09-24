package com.maodouchat.conversation

import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import kotlinx.coroutines.flow.Flow

/**
 * 已读回执的**读取端口**（G64）。
 *
 * `ui/` 层不得直接 import `com.maodouchat.data.local.dao.*`（`ClientArchitectureTest` 的棘轮）——
 * 写库/读库只能经 ViewModel / repository。这个接口就是那条边界的落点：
 * `ui/` 只认识这个抽象，DAO 的具体类型由 `ChatDetailViewModel` 在装配处适配进来。
 *
 * 只暴露协调器真正用到的两个读操作，不继承 DAO 的其它方法——端口收得越窄，
 * 「UI 意外拿到写能力」的面就越小。
 */
interface ReadReceiptSource {
    suspend fun getReceiptsForMessage(ownerUserId: String, messageId: String): List<MessagingV2ReceiptEntity>

    fun observeReceiptsForConversation(ownerUserId: String, conversationId: String): Flow<List<MessagingV2ReceiptEntity>>
}
