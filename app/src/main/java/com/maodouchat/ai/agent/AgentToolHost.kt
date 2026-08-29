package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.ai.AiPromptSafetyPolicy
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
import com.maodouchat.data.repository.MessageSearchRepository
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.messaging.v2.MessagingV2Event
import com.maodouchat.messaging.v2.MessagingV2EventAction
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.security.ChatLockSession
import com.maodouchat.util.JsonFormat
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

object AgentToolHost {
    suspend fun execute(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        val app = MaodouchatApp.instance
        val userId = TokenManager.getInstance(app).getUserId().orEmpty()
        if (userId.isBlank()) return "Error: not signed in"
        return when (name) {
            "list_chats" -> listChats(app, args["query"])
            "get_chat_history" -> getChatHistory(app, args["chatId"].orEmpty(), args["limit"]?.toIntOrNull() ?: 20)
            "search_messages" -> searchMessages(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 12)
            "get_contacts" -> getContacts(app, args["query"])
            "get_me" -> getMe(app, userId)
            "get_chat" -> getChat(app, args["chatId"].orEmpty())
            "list_starred_messages" -> listStarred(app, args["limit"]?.toIntOrNull() ?: 20)
            "list_drafts" -> listDrafts(app, userId)
            "get_draft" -> getDraft(app, userId, args["chatId"].orEmpty())
            "list_local_tasks" -> listTasks(app, args["limit"]?.toIntOrNull() ?: 20)
            "list_missed_calls" -> listMissedCalls(app, args["limit"]?.toIntOrNull() ?: 12)
            "list_notifications" -> listNotifications(app, args["limit"]?.toIntOrNull() ?: 20)
            "create_local_task" -> createTask(app, args["chatId"].orEmpty(), args["title"].orEmpty(), args["dueText"])
            "send_text_message" -> sendText(app, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
            "update_chat" -> updateChat(app, args)
            "set_draft" -> setDraft(app, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
            "star_message" -> starMessage(app, args["messageId"].orEmpty(), parseBool(args["starred"]))
            "complete_local_task" -> completeTask(app, args["taskId"].orEmpty(), parseBool(args["completed"]) == true)
            "delete_local_task" -> deleteTask(app, args["taskId"].orEmpty())
            "set_contact_nickname" -> setNickname(app, args["userId"].orEmpty(), args["nickname"].orEmpty())
            "delete_local_message" -> deleteLocalMessage(app, args["messageId"].orEmpty())
            "mark_notification_read" -> markNotificationRead(app, args["itemId"].orEmpty())
            "list_pinned_messages" -> listPinned(app, args["chatId"].orEmpty())
            "list_friend_requests" -> listFriendRequests(app, args["direction"].orEmpty())
            "list_friends" -> listFriends(app)
            "search_users" -> searchUsers(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 20)
            "list_posts" -> listPosts(app, args["limit"]?.toIntOrNull() ?: 20)
            "get_post" -> getPost(app, args["postId"].orEmpty())
            "list_post_comments" -> listPostComments(app, args["postId"].orEmpty(), args["limit"]?.toIntOrNull() ?: 30)
            "list_blocked_users" -> listBlocked(app)
            "revoke_message" -> revokeMessage(app, args["messageId"].orEmpty())
            "react_to_message" -> react(app, args["messageId"].orEmpty(), args["emoji"].orEmpty())
            "pin_message" -> pinMessage(app, args["chatId"].orEmpty(), args["messageId"].orEmpty())
            "send_friend_request" -> sendFriend(app, args["userId"].orEmpty(), args["message"].orEmpty())
            "accept_friend_request" -> acceptFriend(app, args["requestId"].orEmpty())
            "reject_friend_request" -> rejectFriend(app, args["requestId"].orEmpty())
            "cancel_friend_request" -> cancelFriend(app, args["requestId"].orEmpty())
            "remove_friend" -> removeFriend(app, args["userId"].orEmpty())
            "block_user" -> blockUser(app, args["userId"].orEmpty())
            "unblock_user" -> unblockUser(app, args["userId"].orEmpty())
            "create_text_post" -> createPost(app, args["text"].orEmpty(), args["visibility"])
            "like_post" -> likePost(app, args["postId"].orEmpty(), parseBool(args["liked"]))
            "comment_on_post" -> commentPost(app, args["postId"].orEmpty(), args["text"].orEmpty())
            "delete_post" -> deletePost(app, args["postId"].orEmpty())
            "create_direct_chat" -> createDirect(app, args["userId"].orEmpty())
            "create_group" -> createGroup(app, args["name"].orEmpty(), args["memberIds"].orEmpty())
            "rename_group" -> renameGroup(app, args["chatId"].orEmpty(), args["name"].orEmpty())
            "update_group_announcement" -> updateAnnouncement(app, args["chatId"].orEmpty(), args["announcement"].orEmpty())
            "add_group_members" -> addMembers(app, args["chatId"].orEmpty(), args["memberIds"].orEmpty())
            "remove_group_member" -> removeMember(app, args["chatId"].orEmpty(), args["memberId"].orEmpty())
            "mute_group_member" -> muteMember(app, args["chatId"].orEmpty(), args["memberId"].orEmpty(), args["mutedUntil"]?.toLongOrNull() ?: 0L)
            "delete_chat" -> deleteChat(app, args["chatId"].orEmpty())
            "rewrite_text" -> "Error: rewrite_text is handled by the engine, not the host"
            else -> "Error: unknown tool $name"
        }.take(AgentToolPolicy.MAX_TOOL_RESULT_CHARS)
    }

    fun preview(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        return when (name) {
            "send_text_message" ->
                "发到 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(160)}"
            "create_local_task" ->
                "任务「${args["title"].orEmpty().take(80)}」→ ${args["chatId"].orEmpty().take(24)}"
            "update_chat" ->
                "改会话 ${args["chatId"].orEmpty().take(24)} pinned=${args["pinned"]} muted=${args["muted"]} archived=${args["archived"]} unread=${args["markedUnread"]}"
            "set_draft" ->
                "草稿 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(80).ifBlank { "(清空)" }}"
            "star_message" ->
                "星标 ${args["messageId"].orEmpty().take(24)} → ${args["starred"]}"
            "complete_local_task" ->
                "待办 ${args["taskId"].orEmpty().take(24)} completed=${args["completed"]}"
            "delete_local_task" ->
                "删除待办 ${args["taskId"].orEmpty().take(24)}"
            "set_contact_nickname" ->
                "备注 ${args["userId"].orEmpty().take(24)} → ${args["nickname"].orEmpty().take(40).ifBlank { "(清除)" }}"
            "delete_local_message" ->
                "本机删除消息 ${args["messageId"].orEmpty().take(24)}"
            "mark_notification_read" ->
                "通知已读 ${args["itemId"].orEmpty().take(24)}"
            "revoke_message" -> "撤回 ${args["messageId"].orEmpty().take(24)}"
            "react_to_message" -> "反应 ${args["emoji"].orEmpty()} → ${args["messageId"].orEmpty().take(24)}"
            "pin_message" -> "置顶消息 ${args["messageId"].orEmpty().take(24)}"
            "send_friend_request" -> "好友申请 ${args["userId"].orEmpty().take(24)}"
            "accept_friend_request" -> "同意好友 ${args["requestId"].orEmpty().take(24)}"
            "reject_friend_request" -> "拒绝好友 ${args["requestId"].orEmpty().take(24)}"
            "create_text_post" -> "发动态：${args["text"].orEmpty().take(80)}"
            "like_post" -> "赞动态 ${args["postId"].orEmpty().take(24)} liked=${args["liked"]}"
            "comment_on_post" -> "评论动态 ${args["postId"].orEmpty().take(24)}"
            "delete_post" -> "删除动态 ${args["postId"].orEmpty().take(24)}"
            "create_direct_chat" -> "开聊 ${args["userId"].orEmpty().take(24)}"
            "create_group" -> "建群 ${args["name"].orEmpty().take(40)}"
            "rename_group" -> "改群名 ${args["name"].orEmpty().take(40)}"
            "add_group_members" -> "拉人进群 ${args["memberIds"].orEmpty().take(80)}"
            "remove_group_member" -> "移出群 ${args["memberId"].orEmpty().take(24)}"
            "mute_group_member" -> "禁言 ${args["memberId"].orEmpty().take(24)}"
            "block_user" -> "拉黑 ${args["userId"].orEmpty().take(24)}"
            "unblock_user" -> "取消拉黑 ${args["userId"].orEmpty().take(24)}"
            "delete_chat" -> "删除会话 ${args["chatId"].orEmpty().take(24)}"
            else -> "$name ${argumentsJson.take(160)}"
        }
    }

    internal fun parseArgs(raw: String): Map<String, String> {
        val obj = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key) ?: return@forEach
            out[key] = value.toString()
        }
        return out
    }

    private fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    private suspend fun requireReadableChat(app: MaodouchatApp, chatId: String): String? {
        if (chatId.isBlank()) return "Error: chatId required"
        val locked = app.database.chatLockDao().get(chatId) != null
        val secret = app.database.chatDao().isSecretChat(chatId)
        return AgentSecretGatePolicy.denyIfSecretOrLocked(
            isSecret = secret,
            isLocked = locked,
            unlocked = ChatLockSession.isUnlocked(chatId)
        )
    }

    private suspend fun denySecretOrLockedChat(app: MaodouchatApp, chatId: String): String? =
        requireReadableChat(app, chatId)

    private suspend fun listChats(app: MaodouchatApp, query: String?): String {
        val all = app.database.chatDao().getAllChatsDirect()
        val q = query?.trim()?.lowercase().orEmpty()
        val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
        val secret = app.database.chatDao().listSecretChatIds().toHashSet()
        val lines = all.asSequence()
            .filter { chat ->
                if (!AgentSecretGatePolicy.includeInChatList(
                        isSecret = chat.id in secret,
                        isLocked = chat.id in locked,
                        unlocked = ChatLockSession.isUnlocked(chat.id)
                    )
                ) return@filter false
                if (q.isBlank()) true
                else (chat.groupName.orEmpty() + " " + chat.lastMessage).lowercase().contains(q)
            }
            .take(AgentToolPolicy.MAX_LIST_CHATS)
            .map { chat ->
                val title = chat.groupName?.takeIf { it.isNotBlank() }
                    ?: chat.participantIds.split(",").firstOrNull { it.isNotBlank() }
                    ?: chat.id
                val flags = buildList {
                    if (chat.isGroup) add("group")
                    if (chat.chatType == "CHANNEL") add("channel")
                    if (chat.id in secret) add("secret")
                    if (chat.id in locked) add("pin")
                    if (chat.notificationsMuted) add("muted")
                    if (chat.pinnedAt > 0) add("pinned")
                    if (chat.archived) add("archived")
                    if (chat.unreadCount > 0) add("unread=${chat.unreadCount}")
                }.joinToString(",")
                "${chat.id}\t$title\t${chat.lastMessage.take(80)}\t$flags"
            }
            .toList()
        return if (lines.isEmpty()) "No chats." else lines.joinToString("\n")
    }

    private suspend fun getChat(app: MaodouchatApp, chatId: String): String {
        requireReadableChat(app, chatId)?.let { return it }
        val chat = app.database.chatDao().getChatById(chatId) ?: return "Error: chat not found"
        val title = chat.groupName?.takeIf { it.isNotBlank() }
            ?: chat.participantIds
        return buildString {
            appendLine("id=${chat.id}")
            appendLine("title=$title")
            appendLine("type=${chat.chatType}")
            appendLine("muted=${chat.notificationsMuted}")
            appendLine("pinned=${chat.pinnedAt > 0}")
            appendLine("archived=${chat.archived}")
            appendLine("unread=${chat.unreadCount}")
            appendLine("markedUnread=${chat.markedUnread}")
            appendLine("last=${chat.lastMessage.take(160)}")
        }.trim()
    }

    private suspend fun getChatHistory(app: MaodouchatApp, chatId: String, limit: Int): String {
        requireReadableChat(app, chatId)?.let { return it }
        val messages = LocalMessageStore(app.database.messageDao(), app.database)
            .getRecentMessages(chatId, limit.coerceIn(1, AgentToolPolicy.MAX_CHAT_HISTORY))
        if (messages.isEmpty()) return "No messages."
        return messages.asReversed().joinToString("\n") { formatMessage(it) }
    }

    private suspend fun searchMessages(app: MaodouchatApp, query: String, limit: Int): String {
        val q = AiPromptSafetyPolicy.sanitizeQuery(query)
        if (q.isBlank()) return "Error: query required"
        val hits = MessageSearchRepository(app.database)
            .search(q, limit.coerceIn(1, AgentToolPolicy.MAX_SEARCH_HITS))
        if (hits.isEmpty()) return "No matches."
        val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
        val secret = app.database.chatDao().listSecretChatIds().toHashSet()
        return hits
            .filter { hit ->
                AgentSecretGatePolicy.includeInChatList(
                    isSecret = hit.chatId in secret,
                    isLocked = hit.chatId in locked,
                    unlocked = ChatLockSession.isUnlocked(hit.chatId)
                )
            }
            .joinToString("\n") { hit ->
                "${hit.chatId}\t${hit.messageId}\t${hit.searchableText.take(160)}"
            }
            .ifBlank { "No matches." }
    }

    private suspend fun getContacts(app: MaodouchatApp, query: String?): String {
        val q = query?.trim().orEmpty()
        val all = if (q.isBlank()) {
            app.database.userDao().getAllUsers().first()
        } else {
            val escaped = com.maodouchat.data.local.LikeQueryPolicy.escapeForContains(q.take(80))
            if (escaped.isBlank()) emptyList() else app.database.userDao().searchUsers(escaped, 80)
        }
        val lines = all.take(80).map {
            val display = it.nickname?.takeIf(String::isNotBlank) ?: it.name
            "${it.id}\t$display\t${it.status.take(40)}"
        }
        return if (lines.isEmpty()) "No contacts." else lines.joinToString("\n")
    }

    private suspend fun getMe(app: MaodouchatApp, userId: String): String {
        val me = app.database.userDao().getUserById(userId)
        return buildString {
            appendLine("userId=$userId")
            if (me != null) {
                appendLine("name=${me.name}")
                appendLine("nickname=${me.nickname.orEmpty()}")
                appendLine("status=${me.status}")
            }
        }.trim()
    }

    private suspend fun listStarred(app: MaodouchatApp, limit: Int): String {
        val rows = app.database.messageDao().getStarredMessages(limit.coerceIn(1, 40))
        if (rows.isEmpty()) return "No starred messages."
        val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
        val secret = app.database.chatDao().listSecretChatIds().toHashSet()
        return rows
            .filter { msg ->
                AgentSecretGatePolicy.includeInChatList(
                    isSecret = msg.chatId in secret,
                    isLocked = msg.chatId in locked,
                    unlocked = ChatLockSession.isUnlocked(msg.chatId)
                )
            }
            .joinToString("\n") { msg ->
                val text = AiPromptSafetyPolicy.sanitizeContextText(msg.content, 160)
                "${msg.chatId}\t${msg.id}\t$text"
            }
            .ifBlank { "No starred messages." }
    }

    private suspend fun listDrafts(app: MaodouchatApp, userId: String): String {
        val drafts = app.database.chatDraftDao().observeForOwner(userId).first()
        if (drafts.isEmpty()) return "No drafts."
        val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
        val secret = app.database.chatDao().listSecretChatIds().toHashSet()
        val visible = drafts.filter { draft ->
            AgentSecretGatePolicy.includeInChatList(
                isSecret = draft.chatId in secret,
                isLocked = draft.chatId in locked,
                unlocked = ChatLockSession.isUnlocked(draft.chatId)
            )
        }
        if (visible.isEmpty()) return "No drafts."
        return visible.take(40).joinToString("\n") { "${it.chatId}\t${it.text.take(160)}" }
    }

    private suspend fun getDraft(app: MaodouchatApp, userId: String, chatId: String): String {
        denySecretOrLockedChat(app, chatId)?.let { return it }
        if (chatId.isBlank()) return "Error: chatId required"
        val draft = app.database.chatDraftDao().get(userId, chatId)
        return if (draft == null || draft.text.isBlank()) "No draft." else draft.text.take(AgentToolPolicy.MAX_DRAFT_CHARS)
    }

    private suspend fun blockedChatIds(app: MaodouchatApp): Set<String> {
        val secret = app.database.chatDao().listSecretChatIds().toHashSet()
        val locked = app.database.chatLockDao().listLockedChatIds()
            .filterNot { ChatLockSession.isUnlocked(it) }
            .toHashSet()
        return secret + locked
    }

    private suspend fun secretPeerIds(app: MaodouchatApp): Set<String> =
        app.database.chatDao().getAllChatsDirect()
            .asSequence()
            .filter { it.chatType == "SECRET" }
            .flatMap { it.participantIds.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private suspend fun listTasks(app: MaodouchatApp, limit: Int): String {
        val blocked = blockedChatIds(app)
        val tasks = app.database.aiTaskDao().listRecent(limit.coerceIn(1, 80))
            .filter { it.chatId.isBlank() || it.chatId !in blocked }
            .take(limit.coerceIn(1, 40))
        if (tasks.isEmpty()) return "No tasks."
        return tasks.joinToString("\n") { task ->
            "${task.id}\t${task.chatId}\t${task.title}\tcompleted=${task.isCompleted}\tdue=${task.dueText.orEmpty()}"
        }
    }

    private suspend fun listMissedCalls(app: MaodouchatApp, limit: Int): String {
        val secretPeers = secretPeerIds(app)
        val calls = app.database.missedCallDao().observeRecent().first()
            .filter { it.callerId !in secretPeers }
            .take(limit.coerceIn(1, 30))
        if (calls.isEmpty()) return "No missed calls."
        return calls.joinToString("\n") { call ->
            "${call.id}\t${call.callerId}\t${call.callerName}\t${call.callType}\t${call.receivedAt}\tread=${call.isRead}"
        }
    }

    private suspend fun listNotifications(app: MaodouchatApp, limit: Int): String {
        val blocked = blockedChatIds(app)
        val secretPeers = secretPeerIds(app)
        val items = app.notificationCenter.items.value
            .filter { item ->
                val chatId = item.extra["chatId"].orEmpty()
                val callerId = item.extra["callerId"].orEmpty()
                (chatId.isBlank() || chatId !in blocked) &&
                    (callerId.isBlank() || callerId !in secretPeers) &&
                    blocked.none { id -> item.mergeKey.contains(id) || item.deeplink.orEmpty().contains(id) }
            }
            .take(limit.coerceIn(1, 40))
        if (items.isEmpty()) return "No notifications."
        return items.joinToString("\n") { item ->
            "${item.id}\t${item.type}\t${item.title}\t${item.preview.orEmpty().take(80)}\tread=${item.read}"
        }
    }

    private suspend fun createTask(app: MaodouchatApp, chatId: String, title: String, dueText: String?): String {
        val cleanTitle = title.trim().take(300)
        if (chatId.isBlank() || cleanTitle.isBlank()) return "Error: chatId and title required"
        if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
        denySecretOrLockedChat(app, chatId)?.let { return it }
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

    private suspend fun sendText(app: MaodouchatApp, userId: String, chatId: String, text: String): String {
        val body = text.trim().take(AgentToolPolicy.MAX_TEXT_SEND_CHARS)
        if (chatId.isBlank() || body.isBlank()) return "Error: chatId and text required"
        val chat = app.database.chatDao().getChatById(chatId) ?: return "Error: chat not found"
        denySecretOrLockedChat(app, chatId)?.let { return it }
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

    private suspend fun updateChat(app: MaodouchatApp, args: Map<String, String>): String {
        val chatId = args["chatId"].orEmpty()
        if (chatId.isBlank()) return "Error: chatId required"
        if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
        denySecretOrLockedChat(app, chatId)?.let { return it }
        val repo = ChatRepository(app.database.chatDao(), app.database.userDao())
        val changed = mutableListOf<String>()
        parseBool(args["pinned"])?.let {
            if (it) repo.pinChat(chatId) else repo.unpinChat(chatId)
            changed += "pinned=$it"
        }
        parseBool(args["muted"])?.let {
            if (it) repo.muteChat(chatId) else repo.unmuteChat(chatId)
            changed += "muted=$it"
        }
        parseBool(args["archived"])?.let {
            if (it) repo.archiveChat(chatId) else repo.unarchiveChat(chatId)
            changed += "archived=$it"
        }
        parseBool(args["markedUnread"])?.let {
            if (it) repo.markChatUnread(chatId) else repo.markChatRead(chatId)
            changed += "markedUnread=$it"
        }
        if (changed.isEmpty()) return "Error: provide pinned, muted, archived, or markedUnread"
        return "Updated $chatId ${changed.joinToString(" ")}"
    }

    private suspend fun setDraft(app: MaodouchatApp, userId: String, chatId: String, text: String): String {
        if (chatId.isBlank()) return "Error: chatId required"
        if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
        denySecretOrLockedChat(app, chatId)?.let { return it }
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

    private suspend fun starMessage(app: MaodouchatApp, messageId: String, starred: Boolean?): String {
        if (messageId.isBlank() || starred == null) return "Error: messageId and starred required"
        val message = app.database.messageDao().getMessageById(messageId) ?: return "Error: message not found"
        denySecretOrLockedChat(app, message.chatId)?.let { return it }
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

    private suspend fun completeTask(app: MaodouchatApp, taskId: String, completed: Boolean): String {
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

    private suspend fun deleteTask(app: MaodouchatApp, taskId: String): String {
        if (taskId.isBlank()) return "Error: taskId required"
        if (app.database.aiTaskDao().getById(taskId) == null) return "Error: task not found"
        app.database.aiTaskDao().delete(taskId)
        return "Deleted task $taskId"
    }

    private suspend fun setNickname(app: MaodouchatApp, userId: String, nickname: String): String {
        if (userId.isBlank()) return "Error: userId required"
        if (app.database.userDao().getUserById(userId) == null) return "Error: contact not found"
        UserRepository(app.database.userDao()).setNickname(userId, nickname.trim().take(AgentToolPolicy.MAX_NICKNAME_CHARS))
        return if (nickname.isBlank()) "Cleared nickname for $userId" else "Set nickname for $userId"
    }

    private suspend fun deleteLocalMessage(app: MaodouchatApp, messageId: String): String {
        if (messageId.isBlank()) return "Error: messageId required"
        val message = app.database.messageDao().getMessageById(messageId) ?: return "Error: message not found"
        denySecretOrLockedChat(app, message.chatId)?.let { return it }
        LocalMessageStore(app.database.messageDao(), app.database).deleteMessage(messageId)
        return "Deleted local message $messageId"
    }

    private fun markNotificationRead(app: MaodouchatApp, itemId: String): String {
        if (itemId.isBlank()) return "Error: itemId required"
        val exists = app.notificationCenter.items.value.any { it.id == itemId }
        if (!exists) return "Error: notification not found"
        app.notificationCenter.markRead(itemId)
        return "Marked read $itemId"
    }

    private fun formatMessage(message: Message): String {
        val text = AiPromptSafetyPolicy.sanitizeContextText(message.parsedContent(), 500)
        return "${message.timestamp}\t${message.senderId}\t${message.type.name}\t$text"
    }

    private fun token(app: MaodouchatApp): String? =
        TokenManager.getInstance(app).getToken()?.takeIf { it.isNotBlank() }

    private fun ids(raw: String): List<String> =
        raw.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun fail(error: Throwable): String = "Error: ${error.message ?: error.javaClass.simpleName}"

    private suspend fun listPinned(app: MaodouchatApp, chatId: String): String {
        denySecretOrLockedChat(app, chatId)?.let { return it }
        if (chatId.isBlank()) return "Error: chatId required"
        val token = token(app) ?: return "Error: not signed in"
        val result = ApiService.getPinnedMessages(token, chatId).getOrElse { return fail(it) }
        if (result.pins.isEmpty()) return "No pinned messages."
        return result.pins.joinToString("\n") { "${it.messageId}\tby=${it.pinnedBy}\tat=${it.pinnedAt}" }
    }

    private suspend fun listFriendRequests(app: MaodouchatApp, direction: String): String {
        val token = token(app) ?: return "Error: not signed in"
        val incoming = !direction.equals("outgoing", ignoreCase = true)
        val rows = if (incoming) {
            ApiService.getIncomingFriendRequests(token).getOrElse { return fail(it) }
        } else {
            ApiService.getOutgoingFriendRequests(token).getOrElse { return fail(it) }
        }
        if (rows.isEmpty()) return "No friend requests."
        return rows.take(50).joinToString("\n") { req ->
            "${req.id}\t${req.status}\tfrom=${req.fromUser.id}/${req.fromUser.name}\tto=${req.toUser.id}/${req.toUser.name}\t${req.message.take(80)}"
        }
    }

    private suspend fun listFriends(app: MaodouchatApp): String {
        val token = token(app) ?: return "Error: not signed in"
        val rows = ApiService.getFriends(token).getOrElse { return fail(it) }
        if (rows.isEmpty()) return "No friends."
        return rows.take(80).joinToString("\n") { "${it.id}\t${it.name}\t${it.status.take(40)}" }
    }

    private suspend fun searchUsers(app: MaodouchatApp, query: String, limit: Int): String {
        val q = query.trim()
        if (q.isBlank()) return "Error: query required"
        val token = token(app) ?: return "Error: not signed in"
        val rows = ApiService.searchUsers(token, q, limit.coerceIn(1, 30)).getOrElse { return fail(it) }
        if (rows.isEmpty()) return "No users."
        return rows.joinToString("\n") { "${it.id}\t${it.name}\t${it.status.take(40)}" }
    }

    private suspend fun listPosts(app: MaodouchatApp, limit: Int): String {
        val token = token(app) ?: return "Error: not signed in"
        val rows = ApiService.getPosts(token, limit = limit.coerceIn(1, 40)).getOrElse { return fail(it) }
        if (rows.isEmpty()) return "No posts."
        return rows.joinToString("\n") { post ->
            "${post.id}\t${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\t${post.content.take(120)}"
        }
    }

    private suspend fun getPost(app: MaodouchatApp, postId: String): String {
        if (postId.isBlank()) return "Error: postId required"
        val token = token(app) ?: return "Error: not signed in"
        val post = ApiService.getPost(token, postId).getOrElse { return fail(it) }
        return "id=${post.id}\tauthor=${post.author.id}/${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\tmine=${post.isMine}\n${post.content.take(1_000)}"
    }

    private suspend fun listPostComments(app: MaodouchatApp, postId: String, limit: Int): String {
        if (postId.isBlank()) return "Error: postId required"
        val token = token(app) ?: return "Error: not signed in"
        val rows = ApiService.getPostComments(token, postId, limit.coerceIn(1, 100)).getOrElse { return fail(it) }
        if (rows.isEmpty()) return "No comments."
        return rows.joinToString("\n") { "${it.id}\t${it.author.name}\t${it.content.take(160)}" }
    }

    private suspend fun listBlocked(app: MaodouchatApp): String {
        val token = token(app) ?: return "Error: not signed in"
        val rows = ApiService.getBlockedUserDetails(token).getOrElse { return fail(it) }
        if (rows.isEmpty()) return "No blocked users."
        return rows.joinToString("\n") { "${it.id}\t${it.name}" }
    }

    private suspend fun revokeMessage(app: MaodouchatApp, messageId: String): String {
        if (messageId.isBlank()) return "Error: messageId required"
        token(app) ?: return "Error: not signed in"
        val local = app.database.messageDao().getMessageById(messageId)
            ?: return "Error: message not found"
        denySecretOrLockedChat(app, local.chatId)?.let { return it }
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

    private suspend fun react(app: MaodouchatApp, messageId: String, emoji: String): String {
        if (messageId.isBlank() || emoji.isBlank()) return "Error: messageId and emoji required"
        token(app) ?: return "Error: not signed in"
        val local = app.database.messageDao().getMessageById(messageId)
            ?: return "Error: message not found"
        denySecretOrLockedChat(app, local.chatId)?.let { return it }
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

    private suspend fun pinMessage(app: MaodouchatApp, chatId: String, messageId: String): String {
        denySecretOrLockedChat(app, chatId)?.let { return it }
        if (chatId.isBlank() || messageId.isBlank()) return "Error: chatId and messageId required"
        val token = token(app) ?: return "Error: not signed in"
        val result = ApiService.togglePinnedMessage(token, chatId, messageId).getOrElse { return fail(it) }
        return "Pinned=${result.pinned} for $messageId"
    }

    private suspend fun sendFriend(app: MaodouchatApp, userId: String, message: String): String {
        if (userId.isBlank()) return "Error: userId required"
        val token = token(app) ?: return "Error: not signed in"
        val result = ApiService.sendFriendRequest(token, userId, message.take(300)).getOrElse { return fail(it) }
        return "Friend request ${result.id} status=${result.status}"
    }

    private suspend fun acceptFriend(app: MaodouchatApp, requestId: String): String {
        if (requestId.isBlank()) return "Error: requestId required"
        val token = token(app) ?: return "Error: not signed in"
        val result = ApiService.acceptFriendRequest(token, requestId).getOrElse { return fail(it) }
        return "Accepted ${result.id} status=${result.status}"
    }

    private suspend fun rejectFriend(app: MaodouchatApp, requestId: String): String {
        if (requestId.isBlank()) return "Error: requestId required"
        val token = token(app) ?: return "Error: not signed in"
        val result = ApiService.rejectFriendRequest(token, requestId).getOrElse { return fail(it) }
        return "Rejected ${result.id} status=${result.status}"
    }

    private suspend fun cancelFriend(app: MaodouchatApp, requestId: String): String {
        if (requestId.isBlank()) return "Error: requestId required"
        val token = token(app) ?: return "Error: not signed in"
        val result = ApiService.cancelFriendRequest(token, requestId).getOrElse { return fail(it) }
        return "Cancelled ${result.id} status=${result.status}"
    }

    private suspend fun removeFriend(app: MaodouchatApp, userId: String): String {
        if (userId.isBlank()) return "Error: userId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.removeFriend(token, userId).getOrElse { return fail(it) }
        return "Removed friend $userId"
    }

    private suspend fun blockUser(app: MaodouchatApp, userId: String): String {
        if (userId.isBlank()) return "Error: userId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.blockUser(token, userId).getOrElse { return fail(it) }
        return "Blocked $userId"
    }

    private suspend fun unblockUser(app: MaodouchatApp, userId: String): String {
        if (userId.isBlank()) return "Error: userId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.unblockUser(token, userId).getOrElse { return fail(it) }
        return "Unblocked $userId"
    }

    private suspend fun createPost(app: MaodouchatApp, text: String, visibility: String?): String {
        val body = text.trim().take(2_000)
        if (body.isBlank()) return "Error: text required"
        val token = token(app) ?: return "Error: not signed in"
        val vis = visibility?.trim()?.uppercase()?.takeIf { it in setOf("PUBLIC", "CONTACTS", "PRIVATE") }
        val post = ApiService.createPost(token, body, emptyList(), vis).getOrElse { return fail(it) }
        return "Created post ${post.id}"
    }

    private suspend fun likePost(app: MaodouchatApp, postId: String, liked: Boolean?): String {
        if (postId.isBlank() || liked == null) return "Error: postId and liked required"
        val token = token(app) ?: return "Error: not signed in"
        val post = if (liked) {
            ApiService.likePost(token, postId).getOrElse { return fail(it) }
        } else {
            ApiService.unlikePost(token, postId).getOrElse { return fail(it) }
        }
        return "Post ${post.id} likedByMe=${post.likedByMe} likes=${post.likeCount}"
    }

    private suspend fun commentPost(app: MaodouchatApp, postId: String, text: String): String {
        val body = text.trim().take(800)
        if (postId.isBlank() || body.isBlank()) return "Error: postId and text required"
        val token = token(app) ?: return "Error: not signed in"
        val comment = ApiService.createPostComment(token, postId, body).getOrElse { return fail(it) }
        return "Commented ${comment.id} on $postId"
    }

    private suspend fun deletePost(app: MaodouchatApp, postId: String): String {
        if (postId.isBlank()) return "Error: postId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.deletePost(token, postId).getOrElse { return fail(it) }
        return "Deleted post $postId"
    }

    private suspend fun createDirect(app: MaodouchatApp, userId: String): String {
        if (userId.isBlank()) return "Error: userId required"
        val token = token(app) ?: return "Error: not signed in"
        val chat = ApiService.createChat(token, listOf(userId), isGroup = false).getOrElse { return fail(it) }
        return "Direct chat ${chat.id}"
    }

    private suspend fun createGroup(app: MaodouchatApp, name: String, memberIds: String): String {
        val groupName = name.trim().take(50)
        val members = ids(memberIds)
        if (groupName.isBlank() || members.isEmpty()) return "Error: name and memberIds required"
        val token = token(app) ?: return "Error: not signed in"
        val chat = ApiService.createChat(token, members, isGroup = true, groupName = groupName).getOrElse { return fail(it) }
        return "Group ${chat.id} name=${chat.groupName.orEmpty()}"
    }

    private suspend fun renameGroup(app: MaodouchatApp, chatId: String, name: String): String {
        val groupName = name.trim().take(50)
        if (chatId.isBlank() || groupName.isBlank()) return "Error: chatId and name required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.renameGroup(token, chatId, groupName).getOrElse { return fail(it) }
        return "Renamed $chatId to $groupName"
    }

    private suspend fun updateAnnouncement(app: MaodouchatApp, chatId: String, announcement: String): String {
        if (chatId.isBlank()) return "Error: chatId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.updateGroupAnnouncement(token, chatId, announcement.take(1_200)).getOrElse { return fail(it) }
        return "Updated announcement for $chatId"
    }

    private suspend fun addMembers(app: MaodouchatApp, chatId: String, memberIds: String): String {
        val members = ids(memberIds)
        if (chatId.isBlank() || members.isEmpty()) return "Error: chatId and memberIds required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.addGroupMembers(token, chatId, members).getOrElse { return fail(it) }
        return "Added ${members.size} members to $chatId"
    }

    private suspend fun removeMember(app: MaodouchatApp, chatId: String, memberId: String): String {
        if (chatId.isBlank() || memberId.isBlank()) return "Error: chatId and memberId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.removeGroupMember(token, chatId, memberId).getOrElse { return fail(it) }
        return "Removed $memberId from $chatId"
    }

    private suspend fun muteMember(app: MaodouchatApp, chatId: String, memberId: String, mutedUntil: Long): String {
        if (chatId.isBlank() || memberId.isBlank()) return "Error: chatId and memberId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.updateMemberMute(token, chatId, memberId, mutedUntil.coerceAtLeast(0L)).getOrElse { return fail(it) }
        return "Muted $memberId until $mutedUntil"
    }

    private suspend fun deleteChat(app: MaodouchatApp, chatId: String): String {
        denySecretOrLockedChat(app, chatId)?.let { return it }
        if (chatId.isBlank()) return "Error: chatId required"
        val token = token(app) ?: return "Error: not signed in"
        ApiService.deleteChat(token, chatId).getOrElse { return fail(it) }
        ChatRepository(app.database.chatDao(), app.database.userDao()).deleteChat(chatId)
        return "Deleted chat $chatId"
    }
}
