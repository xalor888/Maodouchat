package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.MessageSearchRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.security.ChatLockSession
import kotlinx.coroutines.flow.first

// Agent 工具执行簇拆分：只读查询工具（会话/消息/联系人/草稿/任务/通话/通知/社交/动态/置顶）。
// 纯搬移：函数体与 AgentToolHost 原版逐字一致；写操作簇已搬出为同包 AgentMessagingWriteTools。
internal object AgentMessagingReadTools {
        internal suspend fun requireReadableChat(app: MaodouchatApp, chatId: String): String? {
            if (chatId.isBlank()) return "Error: chatId required"
            val caps = app.secretConversationController.capabilities(chatId)
            if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.AI)) {
                return "Error: secret chats cannot be accessed by AI"
            }
            return AgentSecretGatePolicy.denyIfSecretOrLocked(
                isSecret = caps.isSecretChat,
                isLocked = caps.isLocked,
                unlocked = ChatLockSession.isUnlocked(chatId)
            )
        }

        internal suspend fun denySecretOrLockedChat(app: MaodouchatApp, chatId: String): String? =
            requireReadableChat(app, chatId)

        internal suspend fun listChats(app: MaodouchatApp, query: String?): String {
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

        internal suspend fun getChat(app: MaodouchatApp, chatId: String): String {
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

        internal suspend fun getChatHistory(app: MaodouchatApp, chatId: String, limit: Int): String {
            requireReadableChat(app, chatId)?.let { return it }
            val messages = LocalMessageStore(app.database.messageDao(), app.database)
                .getRecentMessages(chatId, limit.coerceIn(1, AgentToolPolicy.MAX_CHAT_HISTORY))
            if (messages.isEmpty()) return "No messages."
            return messages.asReversed().joinToString("\n") { formatMessage(it) }
        }

        internal suspend fun searchMessages(app: MaodouchatApp, query: String, limit: Int): String {
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

        internal suspend fun getContacts(app: MaodouchatApp, query: String?): String {
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

        internal suspend fun getMe(app: MaodouchatApp, userId: String): String {
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

        internal suspend fun listStarred(app: MaodouchatApp, limit: Int): String {
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

        internal suspend fun listDrafts(app: MaodouchatApp, userId: String): String {
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

        internal suspend fun getDraft(app: MaodouchatApp, userId: String, chatId: String): String {
            denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val draft = app.database.chatDraftDao().get(userId, chatId)
            return if (draft == null || draft.text.isBlank()) "No draft." else draft.text.take(AgentToolPolicy.MAX_DRAFT_CHARS)
        }

        internal suspend fun blockedChatIds(app: MaodouchatApp): Set<String> {
            val secret = app.database.chatDao().listSecretChatIds().toHashSet()
            val locked = app.database.chatLockDao().listLockedChatIds()
                .filterNot { ChatLockSession.isUnlocked(it) }
                .toHashSet()
            return secret + locked
        }

        internal suspend fun secretPeerIds(app: MaodouchatApp): Set<String> =
            app.database.chatDao().getAllChatsDirect()
                .asSequence()
                .filter { it.chatType == "SECRET" }
                .flatMap { it.participantIds.split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()

        internal suspend fun listTasks(app: MaodouchatApp, limit: Int): String {
            val blocked = blockedChatIds(app)
            val tasks = app.database.aiTaskDao().listRecent(limit.coerceIn(1, 80))
                .filter { it.chatId.isBlank() || it.chatId !in blocked }
                .take(limit.coerceIn(1, 40))
            if (tasks.isEmpty()) return "No tasks."
            return tasks.joinToString("\n") { task ->
                "${task.id}\t${task.chatId}\t${task.title}\tcompleted=${task.isCompleted}\tdue=${task.dueText.orEmpty()}"
            }
        }

        internal suspend fun listMissedCalls(app: MaodouchatApp, limit: Int): String {
            val secretPeers = secretPeerIds(app)
            val calls = app.database.missedCallDao().observeRecent().first()
                .filter { it.callerId !in secretPeers }
                .take(limit.coerceIn(1, 30))
            if (calls.isEmpty()) return "No missed calls."
            return calls.joinToString("\n") { call ->
                "${call.id}\t${call.callerId}\t${call.callerName}\t${call.callType}\t${call.receivedAt}\tread=${call.isRead}"
            }
        }

        internal suspend fun listNotifications(app: MaodouchatApp, limit: Int): String {
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

        internal fun formatMessage(message: Message): String {
            val text = AiPromptSafetyPolicy.sanitizeContextText(message.parsedContent(), 500)
            return "${message.timestamp}\t${message.senderId}\t${message.type.name}\t$text"
        }

        internal suspend fun listPinned(app: MaodouchatApp, chatId: String): String {
            denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.getPinnedMessages(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            if (result.pins.isEmpty()) return "No pinned messages."
            return result.pins.joinToString("\n") { "${it.messageId}\tby=${it.pinnedBy}\tat=${it.pinnedAt}" }
        }

        internal suspend fun listFriendRequests(app: MaodouchatApp, direction: String): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val incoming = !direction.equals("outgoing", ignoreCase = true)
            val rows = if (incoming) {
                ApiService.getIncomingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.getOutgoingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            }
            if (rows.isEmpty()) return "No friend requests."
            return rows.take(50).joinToString("\n") { req ->
                "${req.id}\t${req.status}\tfrom=${req.fromUser.id}/${req.fromUser.name}\tto=${req.toUser.id}/${req.toUser.name}\t${req.message.take(80)}"
            }
        }

        internal suspend fun listFriends(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getFriends(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No friends."
            return rows.take(80).joinToString("\n") { "${it.id}\t${it.name}\t${it.status.take(40)}" }
        }

        internal suspend fun searchUsers(app: MaodouchatApp, query: String, limit: Int): String {
            val q = query.trim()
            if (q.isBlank()) return "Error: query required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.searchUsers(token, q, limit.coerceIn(1, 30)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}\t${it.status.take(40)}" }
        }

        internal suspend fun listPosts(app: MaodouchatApp, limit: Int): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getPosts(token, limit = limit.coerceIn(1, 40)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No posts."
            return rows.joinToString("\n") { post ->
                "${post.id}\t${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\t${post.content.take(120)}"
            }
        }

        internal suspend fun getPost(app: MaodouchatApp, postId: String): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val post = ApiService.getPost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            return "id=${post.id}\tauthor=${post.author.id}/${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\tmine=${post.isMine}\n${post.content.take(1_000)}"
        }

        internal suspend fun listPostComments(app: MaodouchatApp, postId: String, limit: Int): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getPostComments(token, postId, limit.coerceIn(1, 100)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No comments."
            return rows.joinToString("\n") { "${it.id}\t${it.author.name}\t${it.content.take(160)}" }
        }

        internal suspend fun listBlocked(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getBlockedUserDetails(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No blocked users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}" }
        }
}
