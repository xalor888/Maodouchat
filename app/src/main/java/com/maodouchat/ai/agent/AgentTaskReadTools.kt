package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.security.ChatLockSession
import kotlinx.coroutines.flow.first

// Agent 工具执行簇：任务/草稿/通话/通知只读查询。
// 从 AgentMessagingReadTools 按簇搬出，函数体逐字一致；密聊/锁定过滤复用 AgentMessagingReadTools 的门闩。
internal object AgentTaskReadTools {
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
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val draft = app.database.chatDraftDao().get(userId, chatId)
            return if (draft == null || draft.text.isBlank()) "No draft." else draft.text.take(AgentToolPolicy.MAX_DRAFT_CHARS)
        }

        internal suspend fun listTasks(app: MaodouchatApp, limit: Int): String {
            val blocked = AgentMessagingReadTools.blockedChatIds(app)
            val tasks = app.database.aiTaskDao().listRecent(limit.coerceIn(1, 80))
                .filter { it.chatId.isBlank() || it.chatId !in blocked }
                .take(limit.coerceIn(1, 40))
            if (tasks.isEmpty()) return "No tasks."
            return tasks.joinToString("\n") { task ->
                "${task.id}\t${task.chatId}\t${task.title}\tcompleted=${task.isCompleted}\tdue=${task.dueText.orEmpty()}"
            }
        }

        internal suspend fun listMissedCalls(app: MaodouchatApp, limit: Int): String {
            val secretPeers = AgentMessagingReadTools.secretPeerIds(app)
            val calls = app.database.missedCallDao().observeRecent().first()
                .filter { it.callerId !in secretPeers }
                .take(limit.coerceIn(1, 30))
            if (calls.isEmpty()) return "No missed calls."
            return calls.joinToString("\n") { call ->
                "${call.id}\t${call.callerId}\t${call.callerName}\t${call.callType}\t${call.receivedAt}\tread=${call.isRead}"
            }
        }

        internal suspend fun listNotifications(app: MaodouchatApp, limit: Int): String {
            val blocked = AgentMessagingReadTools.blockedChatIds(app)
            val secretPeers = AgentMessagingReadTools.secretPeerIds(app)
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
}
