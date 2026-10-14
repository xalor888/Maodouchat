package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message

data class StarredMessagesUiState(
    val chat: Chat? = null,
    val chatsById: Map<String, Chat> = emptyMap(),
    val messages: List<Message> = emptyList(),
    val currentUserId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    /** true when opened without a chat scope (settings / all favorites) */
    val globalScope: Boolean = false,
    /** Chat-scoped secret mode (for blind watermark / FLAG_SECURE surface). */
    val isSecretChat: Boolean = false,
    val secretChatId: String = "",
    /** Chat-scoped PIN lock: true when this chat is PIN-locked (and the feature is enabled). */
    val isChatLocked: Boolean = false,
    /** True after the user has unlocked the PIN for this session. */
    val isChatUnlocked: Boolean = false,
)
