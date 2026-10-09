package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.security.ChatLockSession
import kotlinx.coroutines.flow.first

// Agent 工具执行簇：草稿只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskDraftReads {
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
        AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
        if (chatId.isBlank()) return "Error: chatId required"
        val draft = app.database.chatDraftDao().get(userId, chatId)
        return if (draft == null || draft.text.isBlank()) "No draft." else draft.text.take(AgentToolPolicy.MAX_DRAFT_CHARS)
    }
}
