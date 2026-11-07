package com.maodouchat.core.model

data class SettingsToggleMutation(
    val optimistic: Chat,
    val request: UpdateChatSettingsRequest,
)
