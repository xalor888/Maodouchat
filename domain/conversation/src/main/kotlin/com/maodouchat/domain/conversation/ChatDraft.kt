package com.maodouchat.domain.conversation

/** 会话列表草稿的纯模型：Room 的 ChatDraftEntity 在 app 侧调用点映射而来。 */
data class ChatDraft(
    val chatId: String,
    val text: String,
    val updatedAt: Long,
)
