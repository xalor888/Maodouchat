package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.ChatSettingsToggle
import com.maodouchat.core.model.SettingsToggleMutation

internal fun bumpOptimisticSettingsClock(chat: Chat, nowMs: Long): Chat =
    com.maodouchat.core.model.bumpOptimisticSettingsClock(chat, nowMs)

internal fun applyConfirmedSettings(optimistic: Chat, settings: ChatSettingsResponse): Chat =
    com.maodouchat.core.model.applyConfirmedSettings(optimistic, settings)

internal fun buildSettingsToggle(
    chat: Chat,
    toggle: ChatSettingsToggle,
    nowMs: Long = System.currentTimeMillis(),
): SettingsToggleMutation =
    com.maodouchat.core.model.buildSettingsToggle(chat, toggle, nowMs)
