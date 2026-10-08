package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.messaging.v2.MessagingV2Event
import com.maodouchat.messaging.v2.MessagingV2EventAction
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.util.JsonFormat
import java.util.UUID

// Agent 工具执行簇：消息级写操作（发送/草稿/星标/置顶/撤回/回应/联系人备注/通知已读/会话设置）。
// 任务/好友/动态/群组四个簇已搬出为同包四个对象（函数体逐字搬移）；token/fail/parseBool 仍由 AgentToolHost 提供。
internal object AgentMessagingWriteTools {
        internal suspend fun sendText(app: MaodouchatApp, userId: String, chatId: String, text: String): String {
            val body = text.trim().take(AgentToolPolicy.MAX_TEXT_SEND_CHARS)
            if (chatId.isBlank() || body.isBlank()) return "Error: chatId and text required"
            val chat = app.database.chatDao().getChatById(chatId) ?: return "Error: chat not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
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

        internal suspend fun updateChat(app: MaodouchatApp, args: Map<String, String>): String {
            val chatId = args["chatId"].orEmpty()
            if (chatId.isBlank()) return "Error: chatId required"
            if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            val repo = ChatRepository(app.database.chatDao(), app.database.userDao())
            val changed = mutableListOf<String>()
            AgentToolHost.parseBool(args["pinned"])?.let {
                if (it) repo.pinChat(chatId) else repo.unpinChat(chatId)
                changed += "pinned=$it"
            }
            AgentToolHost.parseBool(args["muted"])?.let {
                if (it) repo.muteChat(chatId) else repo.unmuteChat(chatId)
                changed += "muted=$it"
            }
            AgentToolHost.parseBool(args["archived"])?.let {
                if (it) repo.archiveChat(chatId) else repo.unarchiveChat(chatId)
                changed += "archived=$it"
            }
            AgentToolHost.parseBool(args["markedUnread"])?.let {
                if (it) repo.markChatUnread(chatId) else repo.markChatRead(chatId)
                changed += "markedUnread=$it"
            }
            if (changed.isEmpty()) return "Error: provide pinned, muted, archived, or markedUnread"
            return "Updated $chatId ${changed.joinToString(" ")}"
        }

        internal suspend fun setDraft(app: MaodouchatApp, userId: String, chatId: String, text: String): String {
            if (chatId.isBlank()) return "Error: chatId required"
            if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
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

        internal suspend fun starMessage(app: MaodouchatApp, messageId: String, starred: Boolean?): String {
            if (messageId.isBlank() || starred == null) return "Error: messageId and starred required"
            val message = app.database.messageDao().getMessageById(messageId) ?: return "Error: message not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, message.chatId)?.let { return it }
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

        internal suspend fun setNickname(app: MaodouchatApp, userId: String, nickname: String): String {
            if (userId.isBlank()) return "Error: userId required"
            if (app.database.userDao().getUserById(userId) == null) return "Error: contact not found"
            UserRepository(app.database.userDao()).setNickname(userId, nickname.trim().take(AgentToolPolicy.MAX_NICKNAME_CHARS))
            return if (nickname.isBlank()) "Cleared nickname for $userId" else "Set nickname for $userId"
        }

        internal suspend fun deleteLocalMessage(app: MaodouchatApp, messageId: String): String {
            if (messageId.isBlank()) return "Error: messageId required"
            val message = app.database.messageDao().getMessageById(messageId) ?: return "Error: message not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, message.chatId)?.let { return it }
            LocalMessageStore(app.database.messageDao(), app.database).deleteMessage(messageId)
            return "Deleted local message $messageId"
        }

        internal fun markNotificationRead(app: MaodouchatApp, itemId: String): String {
            if (itemId.isBlank()) return "Error: itemId required"
            val exists = app.notificationCenter.items.value.any { it.id == itemId }
            if (!exists) return "Error: notification not found"
            app.notificationCenter.markRead(itemId)
            return "Marked read $itemId"
        }

        internal suspend fun revokeMessage(app: MaodouchatApp, messageId: String): String {
            if (messageId.isBlank()) return "Error: messageId required"
            AgentToolHost.token(app) ?: return "Error: not signed in"
            val local = app.database.messageDao().getMessageById(messageId)
                ?: return "Error: message not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, local.chatId)?.let { return it }
            val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
            if (local.senderId != ownerUserId) return "Error: only the sender can revoke this message"
            val chat = app.database.chatDao().getChatById(local.chatId)
            val placeholder = app.getString(com.maodouchat.R.string.chat_message_revoked_placeholder)
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
            AgentMessagingReadTools.denySecretOrLockedChat(app, local.chatId)?.let { return it }
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
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank() || messageId.isBlank()) return "Error: chatId and messageId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.togglePinnedMessage(token, chatId, messageId).getOrElse { return AgentToolHost.fail(it) }
            return "Pinned=${result.pinned} for $messageId"
        }
}
