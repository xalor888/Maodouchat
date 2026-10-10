package com.maodouchat.ui.screen.settings

import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.notification.NotificationInfrastructure
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderScheduler
import kotlinx.coroutines.flow.update

/**
 * 设置勿扰计划：显式开关 + 分钟级窗口（0-1439）。跨天窗口 start>end 表示夜间勿扰。
 * 关闭计划（enabled=false）时窗口数据仍保留，便于下次一键恢复。
 */
internal fun NotificationSettingsViewModel.setDndSchedule(enabled: Boolean, startMinute: Int, endMinute: Int) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.DND)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.dnd_disabled)) }
        return
    }
    val safeStart = startMinute.coerceIn(0, 1439)
    val safeEnd = endMinute.coerceIn(0, 1439)
    if (_uiState.value.dndEnabled == enabled &&
        _uiState.value.dndStartMinute == safeStart &&
        _uiState.value.dndEndMinute == safeEnd
    ) {
        return
    }
    updateLocal {
        it.copy(
            dndEnabled = enabled,
            dndStartMinute = safeStart,
            dndEndMinute = safeEnd,
            dndStartHour = safeStart / 60,
            dndEndHour = safeEnd / 60,
        )
    }
    AiTaskReminderScheduler.requestReconciliation(app, force = true)
    sync()
}

internal fun NotificationSettingsViewModel.isDndActive(): Boolean {
    // UI badge: only when notifications are on, schedule enabled and current time inside DND.
    // Minute math must match FCM + list WS via LocalNotificationSuppressPolicy.
    if (!_uiState.value.enableNotifications) return false
    val context = getApplication<Application>()
    val now = java.util.Calendar.getInstance()
    return com.maodouchat.notification.LocalNotificationSuppressPolicy.shouldSuppress(
        notificationsEnabled = true,
        dndStartHour = _uiState.value.dndStartHour,
        dndEndHour = _uiState.value.dndEndHour,
        hourOfDay = now.get(java.util.Calendar.HOUR_OF_DAY),
        dndRuntimeEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.DND),
        dndEnabled = _uiState.value.dndEnabled,
        startMinute = _uiState.value.dndStartMinute,
        endMinute = _uiState.value.dndEndMinute,
        currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE),
    )
}

internal fun NotificationSettingsViewModel.updateReminderScheduling(notificationsEnabled: Boolean) {
    if (notificationsEnabled && _uiState.value.taskRemindersEnabled) {
        AiTaskReminderScheduler.ensureScheduled(app)
    } else {
        AiTaskReminderScheduler.cancelAll(app)
    }
}

/**
 * Global quiet mode: drop already-posted tray + AI reminders and clear
 * center unread (parity with per-chat mute-on). Safe to call on remote
 * pull when another device / account settings disabled notifications.
 */
internal fun NotificationSettingsViewModel.dismissPostedNotifications() {
    com.maodouchat.notification.NotificationInfrastructure.cancelAll(app)
    com.maodouchat.notification.ReminderNotificationService.cancelAllAiTaskReminders(app)
    com.maodouchat.notification.NotificationCenterAccess.repository.markAllRead()
}
