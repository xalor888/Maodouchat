package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.AiTaskEntity
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

// Agent 工具执行簇拆分第二步：写操作工具（任务/消息/草稿/置顶/好友/动态/群组/会话管理）。
// 纯搬移：函数体与 AgentToolHost 原版逐字一致，调用点改走这里；token/fail/parseBool 仍由 AgentToolHost 提供。
internal object AgentMessagingWriteTools {
        internal suspend fun createTask(app: MaodouchatApp, chatId: String, title: String, dueText: String?): String {
            val cleanTitle = title.trim().take(300)
            if (chatId.isBlank() || cleanTitle.isBlank()) return "Error: chatId and title required"
            if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            val now = System.currentTimeMillis()
            val entity = AiTaskEntity(
                id = "task_${UUID.randomUUID()}",
                chatId = chatId,
                sourceQuery = "agent",
                title = cleanTitle,
                dueText = dueText?.trim()?.take(120)?.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now
            )
            app.database.aiTaskDao().upsertAll(listOf(entity))
            return "Created task ${entity.id}"
        }

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

        internal suspend fun completeTask(app: MaodouchatApp, taskId: String, completed: Boolean): String {
            if (taskId.isBlank()) return "Error: taskId required"
            if (app.database.aiTaskDao().getById(taskId) == null) return "Error: task not found"
            val now = System.currentTimeMillis()
            app.database.aiTaskDao().setCompleted(
                taskId = taskId,
                completed = completed,
                completedAt = if (completed) now else null,
                updatedAt = now
            )
            return "Task $taskId completed=$completed"
        }

        internal suspend fun deleteTask(app: MaodouchatApp, taskId: String): String {
            if (taskId.isBlank()) return "Error: taskId required"
            if (app.database.aiTaskDao().getById(taskId) == null) return "Error: task not found"
            app.database.aiTaskDao().delete(taskId)
            return "Deleted task $taskId"
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

        internal fun ids(raw: String): List<String> =
            raw.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()

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

        internal suspend fun sendFriend(app: MaodouchatApp, userId: String, message: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.sendFriendRequest(token, userId, message.take(300)).getOrElse { return AgentToolHost.fail(it) }
            return "Friend request ${result.id} status=${result.status}"
        }

        internal suspend fun acceptFriend(app: MaodouchatApp, requestId: String): String {
            if (requestId.isBlank()) return "Error: requestId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.acceptFriendRequest(token, requestId).getOrElse { return AgentToolHost.fail(it) }
            return "Accepted ${result.id} status=${result.status}"
        }

        internal suspend fun rejectFriend(app: MaodouchatApp, requestId: String): String {
            if (requestId.isBlank()) return "Error: requestId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.rejectFriendRequest(token, requestId).getOrElse { return AgentToolHost.fail(it) }
            return "Rejected ${result.id} status=${result.status}"
        }

        internal suspend fun cancelFriend(app: MaodouchatApp, requestId: String): String {
            if (requestId.isBlank()) return "Error: requestId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.cancelFriendRequest(token, requestId).getOrElse { return AgentToolHost.fail(it) }
            return "Cancelled ${result.id} status=${result.status}"
        }

        internal suspend fun removeFriend(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.removeFriend(token, userId).getOrElse { return AgentToolHost.fail(it) }
            return "Removed friend $userId"
        }

        internal suspend fun blockUser(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.blockUser(token, userId).getOrElse { return AgentToolHost.fail(it) }
            return "Blocked $userId"
        }

        internal suspend fun unblockUser(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.unblockUser(token, userId).getOrElse { return AgentToolHost.fail(it) }
            return "Unblocked $userId"
        }

        internal suspend fun createPost(app: MaodouchatApp, text: String, visibility: String?): String {
            val body = text.trim().take(2_000)
            if (body.isBlank()) return "Error: text required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            // G154c：可见性白名单与隐私设置页共用一份
            val vis = visibility?.trim()?.uppercase()
                ?.takeIf { com.maodouchat.settings.VISIBILITY_VALUES.contains(it) }
            val post = ApiService.createPost(token, body, emptyList(), vis).getOrElse { return AgentToolHost.fail(it) }
            return "Created post ${post.id}"
        }

        internal suspend fun likePost(app: MaodouchatApp, postId: String, liked: Boolean?): String {
            if (postId.isBlank() || liked == null) return "Error: postId and liked required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val post = if (liked) {
                ApiService.likePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.unlikePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            }
            return "Post ${post.id} likedByMe=${post.likedByMe} likes=${post.likeCount}"
        }

        internal suspend fun commentPost(app: MaodouchatApp, postId: String, text: String): String {
            val body = text.trim().take(800)
            if (postId.isBlank() || body.isBlank()) return "Error: postId and text required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val comment = ApiService.createPostComment(token, postId, body).getOrElse { return AgentToolHost.fail(it) }
            return "Commented ${comment.id} on $postId"
        }

        internal suspend fun deletePost(app: MaodouchatApp, postId: String): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.deletePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            return "Deleted post $postId"
        }

        internal suspend fun createDirect(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val chat = ApiService.createChat(token, listOf(userId), isGroup = false).getOrElse { return AgentToolHost.fail(it) }
            return "Direct chat ${chat.id}"
        }

        internal suspend fun createGroup(app: MaodouchatApp, name: String, memberIds: String): String {
            val groupName = name.trim().take(50)
            val members = ids(memberIds)
            if (groupName.isBlank() || members.isEmpty()) return "Error: name and memberIds required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val chat = ApiService.createChat(token, members, isGroup = true, groupName = groupName).getOrElse { return AgentToolHost.fail(it) }
            return "Group ${chat.id} name=${chat.groupName.orEmpty()}"
        }

        internal suspend fun renameGroup(app: MaodouchatApp, chatId: String, name: String): String {
            val groupName = name.trim().take(50)
            if (chatId.isBlank() || groupName.isBlank()) return "Error: chatId and name required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.renameGroup(token, chatId, groupName).getOrElse { return AgentToolHost.fail(it) }
            return "Renamed $chatId to $groupName"
        }

        internal suspend fun updateAnnouncement(app: MaodouchatApp, chatId: String, announcement: String): String {
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.updateGroupAnnouncement(token, chatId, announcement.take(1_200)).getOrElse { return AgentToolHost.fail(it) }
            return "Updated announcement for $chatId"
        }

        internal suspend fun addMembers(app: MaodouchatApp, chatId: String, memberIds: String): String {
            val members = ids(memberIds)
            if (chatId.isBlank() || members.isEmpty()) return "Error: chatId and memberIds required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.addGroupMembers(token, chatId, members).getOrElse { return AgentToolHost.fail(it) }
            return "Added ${members.size} members to $chatId"
        }

        internal suspend fun removeMember(app: MaodouchatApp, chatId: String, memberId: String): String {
            if (chatId.isBlank() || memberId.isBlank()) return "Error: chatId and memberId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.removeGroupMember(token, chatId, memberId).getOrElse { return AgentToolHost.fail(it) }
            return "Removed $memberId from $chatId"
        }

        internal suspend fun muteMember(app: MaodouchatApp, chatId: String, memberId: String, mutedUntil: Long): String {
            if (chatId.isBlank() || memberId.isBlank()) return "Error: chatId and memberId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.updateMemberMute(token, chatId, memberId, mutedUntil.coerceAtLeast(0L)).getOrElse { return AgentToolHost.fail(it) }
            return "Muted $memberId until $mutedUntil"
        }

        internal suspend fun deleteChat(app: MaodouchatApp, chatId: String): String {
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.deleteChat(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            ChatRepository(app.database.chatDao(), app.database.userDao()).deleteChat(chatId)
            return "Deleted chat $chatId"
        }
}
