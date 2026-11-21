package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.core.model.MessagingV2Event
import com.maodouchat.core.model.MessagingV2EventAction
import com.maodouchat.domain.messaging.AgentMessageErrorPolicy
import com.maodouchat.domain.messaging.AgentMessageResultPolicy
import com.maodouchat.domain.messaging.AgentReactionTogglePolicy
import com.maodouchat.domain.messaging.AgentToolInputPolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import com.maodouchat.network.ApiService

// 消息级操作：星标、本地删除、撤回、回应、置顶。
internal object AgentMessagingMessageTools {
        internal suspend fun starMessage(ports: AgentToolPorts, messageId: String, starred: Boolean?): String {
            if (messageId.isBlank() || starred == null) return AgentWriteErrorPolicy.required("messageId", "starred")
            val message = ports.database.messageDao().getMessageById(messageId) ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, message.chatId)?.let { return it }
            if (message.starred == starred) return AgentMessageResultPolicy.alreadyStarred(starred)
            val token = ports.token.orEmpty()
            if (token.isNotBlank()) {
                var result = ApiService.toggleStarMessage(token, messageId)
                    .getOrElse { return AgentMessageErrorPolicy.starSyncFailed(it.message) }
                if (result.starred != starred) {
                    result = ApiService.toggleStarMessage(token, messageId)
                        .getOrElse { return AgentMessageErrorPolicy.starSyncFailed(it.message) }
                }
                if (result.starred != starred) return AgentMessageResultPolicy.serverStarMismatch(result.starred)
                ports.database.messageDao().setStarred(messageId, result.starred)
                return AgentMessageResultPolicy.starred(result.starred, messageId)
            }
            ports.database.messageDao().setStarred(messageId, starred)
            return AgentMessageResultPolicy.starredLocally(starred, messageId)
        }

        internal suspend fun deleteLocalMessage(ports: AgentToolPorts, messageId: String): String {
            if (messageId.isBlank()) return AgentWriteErrorPolicy.required("messageId")
            val message = ports.database.messageDao().getMessageById(messageId) ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, message.chatId)?.let { return it }
            LocalMessageStore(ports.database.messageDao(), ports.database).deleteMessage(messageId)
            return AgentMessageResultPolicy.deletedLocal(messageId)
        }

        internal suspend fun revokeMessage(ports: AgentToolPorts, messageId: String): String {
            if (messageId.isBlank()) return AgentWriteErrorPolicy.required("messageId")
            ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            val local = ports.database.messageDao().getMessageById(messageId)
                ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, local.chatId)?.let { return it }
            val ownerUserId = ports.userId
            if (local.senderId != ownerUserId) return AgentMessageErrorPolicy.onlySenderCanRevoke()
            val chat = ports.database.chatDao().getChatById(local.chatId)
            val placeholder = ports.getString(R.string.chat_message_revoked_placeholder)
            ports.messagingV2Outbox.enqueueEvent(
                conversationId = local.chatId,
                event = MessagingV2Event(
                    action = MessagingV2EventAction.REVOKE,
                    targetMessageId = messageId,
                    content = placeholder,
                    editedAt = System.currentTimeMillis(),
                ),
                groupRevision = chat?.memberRevision?.takeIf { chat.isGroup },
            )
            val revoked = local.toDomain().copy(
                content = placeholder,
                type = MessageType.REVOKED,
                meta = MessageMeta(),
            )
            LocalMessageStore(ports.database.messageDao(), ports.database).insertMessage(revoked)
            MaodouchatApp.emitChatListPreviewRefresh(local.chatId)
            return AgentMessageResultPolicy.revoked(messageId)
        }

        internal suspend fun react(ports: AgentToolPorts, messageId: String, emoji: String): String {
            if (messageId.isBlank() || emoji.isBlank()) return AgentWriteErrorPolicy.required("messageId", "emoji")
            ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            val local = ports.database.messageDao().getMessageById(messageId)
                ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, local.chatId)?.let { return it }
            val ownerUserId = ports.userId
            val normalizedEmoji = AgentToolInputPolicy.normalizeReactionEmoji(emoji)
            val current = local.toDomain()
            val nextEmoji = AgentReactionTogglePolicy.nextEmoji(current.reactions, ownerUserId, normalizedEmoji)
            val chat = ports.database.chatDao().getChatById(local.chatId)
            ports.messagingV2Outbox.enqueueEvent(
                conversationId = local.chatId,
                event = MessagingV2Event(
                    action = MessagingV2EventAction.REACTION_SET,
                    targetMessageId = messageId,
                    reactionEmoji = nextEmoji,
                ),
                groupRevision = chat?.memberRevision?.takeIf { chat.isGroup },
            )
            val reactions = AgentReactionTogglePolicy.applyReaction(current.reactions, ownerUserId, nextEmoji)
            LocalMessageStore(ports.database.messageDao(), ports.database)
                .updateMessageReactions(messageId, reactions)
            return AgentMessageResultPolicy.reactionUpdated(messageId, reactions.size)
        }

        internal suspend fun pinMessage(ports: AgentToolPorts, chatId: String, messageId: String): String {
            AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
            if (chatId.isBlank() || messageId.isBlank()) return AgentWriteErrorPolicy.required("chatId", "messageId")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            val result = ApiService.togglePinnedMessage(token, chatId, messageId).getOrElse { return AgentToolHost.fail(it) }
            return AgentMessageResultPolicy.pinned(result.pinned, messageId)
        }
}
