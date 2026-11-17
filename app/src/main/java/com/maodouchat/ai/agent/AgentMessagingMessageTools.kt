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
import com.maodouchat.network.TokenManager

// 消息级操作：星标、本地删除、撤回、回应、置顶。
internal object AgentMessagingMessageTools {
        internal suspend fun starMessage(app: MaodouchatApp, messageId: String, starred: Boolean?): String {
            if (messageId.isBlank() || starred == null) return AgentWriteErrorPolicy.required("messageId", "starred")
            val message = app.database.messageDao().getMessageById(messageId) ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(app, message.chatId)?.let { return it }
            if (message.starred == starred) return AgentMessageResultPolicy.alreadyStarred(starred)
            val token = TokenManager.getInstance(app).getToken().orEmpty()
            if (token.isNotBlank()) {
                var result = ApiService.toggleStarMessage(token, messageId)
                    .getOrElse { return AgentMessageErrorPolicy.starSyncFailed(it.message) }
                if (result.starred != starred) {
                    result = ApiService.toggleStarMessage(token, messageId)
                        .getOrElse { return AgentMessageErrorPolicy.starSyncFailed(it.message) }
                }
                if (result.starred != starred) return AgentMessageResultPolicy.serverStarMismatch(result.starred)
                app.database.messageDao().setStarred(messageId, result.starred)
                return AgentMessageResultPolicy.starred(result.starred, messageId)
            }
            app.database.messageDao().setStarred(messageId, starred)
            return AgentMessageResultPolicy.starredLocally(starred, messageId)
        }

        internal suspend fun deleteLocalMessage(app: MaodouchatApp, messageId: String): String {
            if (messageId.isBlank()) return AgentWriteErrorPolicy.required("messageId")
            val message = app.database.messageDao().getMessageById(messageId) ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(app, message.chatId)?.let { return it }
            LocalMessageStore(app.database.messageDao(), app.database).deleteMessage(messageId)
            return AgentMessageResultPolicy.deletedLocal(messageId)
        }

        internal suspend fun revokeMessage(app: MaodouchatApp, messageId: String): String {
            if (messageId.isBlank()) return AgentWriteErrorPolicy.required("messageId")
            AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            val local = app.database.messageDao().getMessageById(messageId)
                ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(app, local.chatId)?.let { return it }
            val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
            if (local.senderId != ownerUserId) return AgentMessageErrorPolicy.onlySenderCanRevoke()
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
            return AgentMessageResultPolicy.revoked(messageId)
        }

        internal suspend fun react(app: MaodouchatApp, messageId: String, emoji: String): String {
            if (messageId.isBlank() || emoji.isBlank()) return AgentWriteErrorPolicy.required("messageId", "emoji")
            AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            val local = app.database.messageDao().getMessageById(messageId)
                ?: return AgentMessageErrorPolicy.messageNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(app, local.chatId)?.let { return it }
            val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
            val normalizedEmoji = AgentToolInputPolicy.normalizeReactionEmoji(emoji)
            val current = local.toDomain()
            val nextEmoji = AgentReactionTogglePolicy.nextEmoji(current.reactions, ownerUserId, normalizedEmoji)
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
            val reactions = AgentReactionTogglePolicy.applyReaction(current.reactions, ownerUserId, nextEmoji)
            LocalMessageStore(app.database.messageDao(), app.database)
                .updateMessageReactions(messageId, reactions)
            return AgentMessageResultPolicy.reactionUpdated(messageId, reactions.size)
        }

        internal suspend fun pinMessage(app: MaodouchatApp, chatId: String, messageId: String): String {
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank() || messageId.isBlank()) return AgentWriteErrorPolicy.required("chatId", "messageId")
            val token = AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            val result = ApiService.togglePinnedMessage(token, chatId, messageId).getOrElse { return AgentToolHost.fail(it) }
            return AgentMessageResultPolicy.pinned(result.pinned, messageId)
        }
}
