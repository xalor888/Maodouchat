package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.network.UpdateChatSettingsRequest

data class SettingsToggleMutation(
    val optimistic: Chat,
    val request: UpdateChatSettingsRequest,
)
