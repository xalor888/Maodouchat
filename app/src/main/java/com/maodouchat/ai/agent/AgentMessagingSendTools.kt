package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.util.JsonFormat
import java.util.UUID

// 消息发送与草稿：出站文本与草稿存取。
internal object AgentMessagingSendTools {
        internal suspend fun sendText(app: MaodouchatApp, userId: String, chatId: String, text: String): String {
            val body = text.trim().take(AgentToolPolicy.MAX_TEXT_SEND_CHARS)
            if (chatId.isBlank() || body.isBlank()) return "Error: chatId and text required"
            val chat = app.database.chatDao().getChatById(chatId) ?: return "Error: chat not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            val meta = MessageMeta(aiAssisted = true, aiAssistantMode = "agent")
            val content = JsonFormat.composeContentWithMeta(body, meta)
            val message = Message(
                id = "m_${UUID.randomUUID()}",
                chatId = chatId,
                senderId = userId,
                content = content,
                type = MessageType.TEXT,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                meta = meta
            )
            val messageStore = LocalMessageStore(app.database.messageDao(), app.database)
            MessagingV2MessageGateway(
                database = app.database,
                messageStore = messageStore,
                outbox = app.messagingV2Outbox,
            ).stageAndEnqueue(
                message = message,
                groupRevision = chat.memberRevision.takeIf { chat.isGroup },
                body = content,
                type = MessageType.TEXT,
            )
            return "Queued ${message.id} to ${if (chat.isGroup) "group" else "direct"} $chatId via E2EE outbox"
        }

        internal suspend fun setDraft(app: MaodouchatApp, userId: String, chatId: String, text: String): String {
            if (chatId.isBlank()) return "Error: chatId required"
            if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            val body = text.trim().take(AgentToolPolicy.MAX_DRAFT_CHARS)
            if (body.isBlank()) {
                app.database.chatDraftDao().delete(userId, chatId)
                return "Cleared draft for $chatId"
            }
            app.database.chatDraftDao().upsert(
                ChatDraftEntity(ownerUserId = userId, chatId = chatId, text = body, updatedAt = System.currentTimeMillis())
            )
            return "Saved draft for $chatId (${body.length} chars)"
        }
}
