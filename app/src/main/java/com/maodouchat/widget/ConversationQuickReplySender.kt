package com.maodouchat.widget

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.data.repository.TextOutboxFlusher
import com.maodouchat.util.AppNotifier
import java.util.UUID

/**
 * B5 快捷回复发送器：仅调用现有公开 API 走完整加密发送链路。
 *
 * 与 ChatDetailViewModel.sendMessage 相同路径：
 *  1. 本地落库 SENDING 乐观消息（明文 content，meta 为空）；
 *  2. TextOutboxFlusher.flush → SignalProtocol 加密（群聊含 sender-key 分发）→ REST 幂等发送；
 *  3. MaodouchatApp.emitMessageSent 让会话列表即时更新预览。
 *
 * 不触碰 MaodouchatApp.kt / AppNotifier.kt / 任何 ViewModel。
 */
object ConversationQuickReplySender {

    /**
     * @return true 表示已受理（含乐观落库）；false 表示发送路径异常
     */
    suspend fun sendQuickReply(
        app: MaodouchatApp,
        chatId: String,
        text: String,
        ownerUserId: String,
    ): Boolean {
        if (chatId.isBlank() || text.isBlank() || ownerUserId.isBlank()) return false
        return try {
            val repo = MessageRepository(app.database.messageDao(), app.database)
            val now = System.currentTimeMillis()
            val optimistic = Message(
                id = "m_${UUID.randomUUID()}",
                chatId = chatId,
                senderId = ownerUserId,
                content = text,
                type = MessageType.TEXT,
                timestamp = now,
                status = MessageStatus.SENDING,
                meta = MessageMeta()
            )
            repo.insertMessage(optimistic)
            // 移除对应托盘通知（与点击通知打开会话的行为一致）
            AppNotifier.cancelMessage(app, chatId)
            // 后台安全 flush：Signal 初始化/加密/发送全部走既有实现
            TextOutboxFlusher.flush(app = app, activeChatId = chatId)
            // 通知会话列表刷新预览
            MaodouchatApp.emitMessageSent(chatId = chatId, previewText = text, messageTypeWire = "TEXT")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
