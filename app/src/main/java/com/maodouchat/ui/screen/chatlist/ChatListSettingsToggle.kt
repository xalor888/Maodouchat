package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.network.ChatSettingsResponse
import com.maodouchat.network.UpdateChatSettingsRequest

data class SettingsToggleMutation(
    val optimistic: Chat,
    val request: UpdateChatSettingsRequest,
)

fun bumpOptimisticSettingsClock(chat: Chat, nowMs: Long): Chat =
    chat.copy(settingsUpdatedAt = maxOf(nowMs, chat.settingsUpdatedAt + 1L))

/**
 * 服务端确认合并（`updateChatSettings` 成功分支逐字搬运）：以服务端快照覆盖
 * 四个设置位与更新时钟，其余本地字段（预览/草稿等）保持乐观值。
 */
fun applyConfirmedSettings(optimistic: Chat, settings: ChatSettingsResponse): Chat =
    optimistic.copy(
        pinnedAt = settings.pinnedAt,
        notificationsMuted = settings.notificationsMuted,
        archived = settings.archived,
        markedUnread = settings.markedUnread,
        settingsUpdatedAt = settings.updatedAt,
    )

fun buildSettingsToggle(
    chat: Chat,
    toggle: ChatSettingsToggle,
    nowMs: Long = System.currentTimeMillis(),
): SettingsToggleMutation = when (toggle) {
    ChatSettingsToggle.PIN -> {
        val pinning = chat.pinnedAt <= 0
        SettingsToggleMutation(
            optimistic = bumpOptimisticSettingsClock(
                chat.copy(pinnedAt = if (chat.pinnedAt > 0) 0 else nowMs),
                nowMs,
            ),
            request = UpdateChatSettingsRequest(pinned = pinning),
        )
    }
    ChatSettingsToggle.MUTE -> SettingsToggleMutation(
        optimistic = bumpOptimisticSettingsClock(
            chat.copy(notificationsMuted = !chat.notificationsMuted),
            nowMs,
        ),
        request = UpdateChatSettingsRequest(notificationsMuted = !chat.notificationsMuted),
    )
    ChatSettingsToggle.ARCHIVE -> {
        val archiving = !chat.archived
        SettingsToggleMutation(
            optimistic = bumpOptimisticSettingsClock(
                chat.copy(
                    archived = archiving,
                    pinnedAt = if (archiving) 0 else chat.pinnedAt,
                ),
                nowMs,
            ),
            request = UpdateChatSettingsRequest(
                archived = archiving,
                pinned = if (archiving) false else null,
            ),
        )
    }
    ChatSettingsToggle.MARKED_UNREAD -> SettingsToggleMutation(
        optimistic = bumpOptimisticSettingsClock(
            chat.copy(markedUnread = !chat.markedUnread),
            nowMs,
        ),
        request = UpdateChatSettingsRequest(markedUnread = !chat.markedUnread),
    )
}
