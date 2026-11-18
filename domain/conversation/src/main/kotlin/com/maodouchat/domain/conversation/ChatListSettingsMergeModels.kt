package com.maodouchat.domain.conversation

data class SettingsSnapshot(
    val pinnedAt: Long,
    val notificationsMuted: Boolean,
    val archived: Boolean,
    val markedUnread: Boolean,
    val settingsUpdatedAt: Long
)
