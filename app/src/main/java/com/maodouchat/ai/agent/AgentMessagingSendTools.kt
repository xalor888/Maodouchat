package com.maodouchat.ai.agent

import com.maodouchat.core.model.MessageStatus
import com.maodouchat.domain.messaging.AgentEntityIdPolicy
import com.maodouchat.domain.messaging.AgentSendResultPolicy
import com.maodouchat.domain.messaging.AgentToolHostPolicy
import com.maodouchat.domain.messaging.AgentWriteInputPolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.domain.messaging.MessageJsonPolicy
import java.util.UUID

// 消息发送与草稿：出站文本与草稿存取。
internal object AgentMessagingSendTools {
        internal suspend fun sendText(ports: AgentToolPorts, userId: String, chatId: String, text: String): String {
            val body = AgentWriteInputPolicy.cleanSendText(text)
            if (chatId.isBlank() || body.isBlank()) return AgentWriteErrorPolicy.required("chatId", "text")
            val chat = ports.database.chatDao().getChatById(chatId) ?: return AgentWriteErrorPolicy.chatNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
            val meta = MessageMeta(aiAssisted = true, aiAssistantMode = AgentToolHostPolicy.AGENT_SOURCE)
            val content = MessageJsonPolicy.composeContentWithMeta(body, meta)
            val message = Message(
                id = AgentEntityIdPolicy.messageId(UUID.randomUUID().toString()),
                chatId = chatId,
                senderId = userId,
                content = content,
                type = MessageType.TEXT,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                meta = meta
            )
            val messageStore = LocalMessageStore(ports.database.messageDao(), ports.database)
            MessagingV2MessageGateway(
                database = ports.database,
                messageStore = messageStore,
                outbox = ports.messagingV2Outbox,
            ).stageAndEnqueue(
                message = message,
                groupRevision = chat.memberRevision.takeIf { chat.isGroup },
                body = content,
                type = MessageType.TEXT,
            )
            return AgentSendResultPolicy.queued(message.id, chat.isGroup, chatId)
        }

        internal suspend fun setDraft(ports: AgentToolPorts, userId: String, chatId: String, text: String): String {
            if (chatId.isBlank()) return AgentWriteErrorPolicy.required("chatId")
            if (ports.database.chatDao().getChatById(chatId) == null) return AgentWriteErrorPolicy.chatNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
            val body = AgentWriteInputPolicy.cleanDraftText(text)
            if (body.isBlank()) {
                ports.database.chatDraftDao().delete(userId, chatId)
                return AgentSendResultPolicy.draftCleared(chatId)
            }
            ports.database.chatDraftDao().upsert(
                ChatDraftEntity(ownerUserId = userId, chatId = chatId, text = body, updatedAt = System.currentTimeMillis())
            )
            return AgentSendResultPolicy.draftSaved(chatId, body.length)
        }
}
