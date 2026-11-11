package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MissedCall

/** 会话列表加载簇读写的状态面：`ChatListUiState` 仍在 app 侧，协调器只经端口读写这 5 个字段。 */
data class ChatListLoadState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val secretChatIds: Set<String> = emptySet(),
    val missedCalls: List<MissedCall> = emptyList(),
)
