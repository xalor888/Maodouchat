package com.maodouchat.ui.screen.settings

/** 「新消息通知」设置页的 UI 状态。 */
data class NotificationSettingsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val enableNotifications: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val previewEnabled: Boolean = true,
    val ringtoneEnabled: Boolean = true,
    val taskRemindersEnabled: Boolean = true,
    val dndStartHour: Int = 22,     // 22:00-07:00
    val dndEndHour: Int = 7,
    /** 勿扰计划：显式开关 + 分钟级窗口（0-1439），默认 22:00-07:00 未启用。 */
    val dndEnabled: Boolean = false,
    val dndStartMinute: Int = 22 * 60,
    val dndEndMinute: Int = 7 * 60,
    /** FCM: config present in BuildConfig / process initialized. */
    val pushConfigured: Boolean = false,
    val pushReady: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)
