package com.maodouchat.ui.screen.chatlist

data class SettingsSnapshot(
    val pinnedAt: Long,
    val notificationsMuted: Boolean,
    val archived: Boolean,
    val markedUnread: Boolean,
    val settingsUpdatedAt: Long
)
