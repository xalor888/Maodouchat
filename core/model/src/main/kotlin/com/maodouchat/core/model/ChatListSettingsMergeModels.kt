package com.maodouchat.core.model

data class SettingsSnapshot(
    val pinnedAt: Long,
    val notificationsMuted: Boolean,
    val archived: Boolean,
    val markedUnread: Boolean,
    val settingsUpdatedAt: Long
)
