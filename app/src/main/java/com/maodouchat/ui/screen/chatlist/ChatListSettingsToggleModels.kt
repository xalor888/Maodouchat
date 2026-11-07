package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.core.model.Chat

data class SettingsToggleMutation(
    val optimistic: Chat,
    val request: UpdateChatSettingsRequest,
)
