package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.UpdateChatSettingsRequest

data class SettingsToggleMutation(
    val optimistic: Chat,
    val request: UpdateChatSettingsRequest,
)
