package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.MessageSearchRepository
import com.maodouchat.network.ApiService
import com.maodouchat.security.ChatLockSession

// Agent 工具执行簇：会话/消息只读查询（密聊门闩、会话列表、详情、历史、搜索、星标、置顶）。
// 任务/草稿/通话/通知簇搬出为 AgentTaskReadTools，社交簇搬出为 AgentSocialReadTools（函数体逐字搬移）。
// 密聊/锁定门闩（requireReadableChat/denySecretOrLockedChat/blockedChatIds/secretPeerIds）留在这里，
// 写操作簇与搬出的读簇复用它们。
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

        internal fun formatMessage(message: Message): String {
            val text = AiPromptSafetyPolicy.sanitizeContextText(message.parsedContent(), 500)
            return "${message.timestamp}\t${message.senderId}\t${message.type.name}\t$text"
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

        internal suspend fun listPinned(app: MaodouchatApp, chatId: String): String {
            denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.getPinnedMessages(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            if (result.pins.isEmpty()) return "No pinned messages."
            return result.pins.joinToString("\n") { "${it.messageId}\tby=${it.pinnedBy}\tat=${it.pinnedAt}" }
        }
}
