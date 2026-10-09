package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.MessagingV2Event
import com.maodouchat.messaging.v2.MessagingV2EventAction
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager

// 消息级操作：星标、本地删除、撤回、回应、置顶。
internal object AgentMessagingMessageTools {
        internal suspend fun starMessage(app: MaodouchatApp, messageId: String, starred: Boolean?): String {
            if (messageId.isBlank() || starred == null) return "Error: messageId and starred required"
            val message = app.database.messageDao().getMessageById(messageId) ?: return "Error: message not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, message.chatId)?.let { return it }
            if (message.starred == starred) return "Already starred=$starred"
            val token = TokenManager.getInstance(app).getToken().orEmpty()
            if (token.isNotBlank()) {
                var result = ApiService.toggleStarMessage(token, messageId)
                    .getOrElse { return "Error: ${it.message ?: "star sync failed"}" }
                if (result.starred != starred) {
                    result = ApiService.toggleStarMessage(token, messageId)
                        .getOrElse { return "Error: ${it.message ?: "star sync failed"}" }
                }
                if (result.starred != starred) return "Error: server star state is ${result.starred}"
                app.database.messageDao().setStarred(messageId, result.starred)
                return "Starred=${result.starred} for $messageId"
            }
            app.database.messageDao().setStarred(messageId, starred)
            return "Starred=$starred locally for $messageId (offline)"
        }

        internal suspend fun deleteLocalMessage(app: MaodouchatApp, messageId: String): String {
            if (messageId.isBlank()) return "Error: messageId required"
            val message = app.database.messageDao().getMessageById(messageId) ?: return "Error: message not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, message.chatId)?.let { return it }
            LocalMessageStore(app.database.messageDao(), app.database).deleteMessage(messageId)
            return "Deleted local message $messageId"
        }

        internal suspend fun revokeMessage(app: MaodouchatApp, messageId: String): String {
            if (messageId.isBlank()) return "Error: messageId required"
            AgentToolHost.token(app) ?: return "Error: not signed in"
            val local = app.database.messageDao().getMessageById(messageId)
                ?: return "Error: message not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, local.chatId)?.let { return it }
            val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
            if (local.senderId != ownerUserId) return "Error: only the sender can revoke this message"
            val chat = app.database.chatDao().getChatById(local.chatId)
            val placeholder = app.getString(R.string.chat_message_revoked_placeholder)
            app.messagingV2Outbox.enqueueEvent(
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
            LocalMessageStore(app.database.messageDao(), app.database).insertMessage(revoked)
            MaodouchatApp.emitChatListPreviewRefresh(local.chatId)
            return "Revoked $messageId"
        }

        internal suspend fun react(app: MaodouchatApp, messageId: String, emoji: String): String {
            if (messageId.isBlank() || emoji.isBlank()) return "Error: messageId and emoji required"
            AgentToolHost.token(app) ?: return "Error: not signed in"
            val local = app.database.messageDao().getMessageById(messageId)
                ?: return "Error: message not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, local.chatId)?.let { return it }
            val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
            val normalizedEmoji = emoji.trim().take(16)
            val current = local.toDomain()
            val nextEmoji = normalizedEmoji.takeUnless {
                current.reactions.any { reaction ->
                    reaction.userId == ownerUserId && reaction.emoji == normalizedEmoji
                }
            }
            val chat = app.database.chatDao().getChatById(local.chatId)
            app.messagingV2Outbox.enqueueEvent(
                conversationId = local.chatId,
                event = MessagingV2Event(
                    action = MessagingV2EventAction.REACTION_SET,
                    targetMessageId = messageId,
                    reactionEmoji = nextEmoji,
                ),
                groupRevision = chat?.memberRevision?.takeIf { chat.isGroup },
            )
            val reactions = current.reactions.filterNot { it.userId == ownerUserId } +
                listOfNotNull(nextEmoji?.let { MessageReaction(ownerUserId, it) })
            LocalMessageStore(app.database.messageDao(), app.database)
                .updateMessageReactions(messageId, reactions)
            return "Reaction updated on $messageId count=${reactions.size}"
        }

        internal suspend fun pinMessage(app: MaodouchatApp, chatId: String, messageId: String): String {
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank() || messageId.isBlank()) return "Error: chatId and messageId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.togglePinnedMessage(token, chatId, messageId).getOrElse { return AgentToolHost.fail(it) }
            return "Pinned=${result.pinned} for $messageId"
        }
}
