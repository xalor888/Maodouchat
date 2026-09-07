package com.maodouchat.widget

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.QuickReplyRequest
import com.maodouchat.quickreply.QuickReplyPolicy

/**
 * M11 快捷回复桥接适配器：
 * 统一切换为调用统一的 [com.maodouchat.domain.messaging.QuickReplyUseCase]，
 * 不再由 Widget 层直接构造伪造消息或直接操作底层持久化。
 */
object ConversationQuickReplySender {

    /**
     * @return true 表示已成功受理入队；false 表示发送校验或路径异常
     */
    suspend fun sendQuickReply(
        app: MaodouchatApp,
        chatId: String,
        text: String,
        ownerUserId: String,
    ): Boolean {
        if (chatId.isBlank() || text.isBlank() || ownerUserId.isBlank()) return false
        val key = QuickReplyPolicy.dedupeKey(ownerUserId, chatId, text)
        val result = app.quickReplyUseCase.reply(
            QuickReplyRequest(
                conversationId = chatId,
                text = text,
                idempotencyKey = key,
            )
        )
        return result.isSuccess
    }
}
