package com.maodouchat.ui.screen.settings

import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.notification.NotificationInfrastructure
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderPreferences
import com.maodouchat.notification.NotificationPreferences
import kotlinx.coroutines.flow.update

internal fun NotificationSettingsViewModel.setEnableNotifications(value: Boolean) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.PUSH_NOTIFICATIONS)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.push_notifications_disabled)) }
        return
    }
    if (_uiState.value.enableNotifications == value) return
    updateLocal { it.copy(enableNotifications = value) }
    updateReminderScheduling(value)
    if (!value) dismissPostedNotifications()
    sync()
}

internal fun NotificationSettingsViewModel.setSoundEnabled(value: Boolean) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_SOUND)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.notification_sound_disabled)) }
        return
    }
    if (_uiState.value.soundEnabled == value) return
    updateLocal { it.copy(soundEnabled = value) }
    sync()
}

internal fun NotificationSettingsViewModel.setPreviewEnabled(value: Boolean) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_PREVIEW)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.notification_preview_disabled)) }
        return
    }
    if (_uiState.value.previewEnabled == value) return
    updateLocal { it.copy(previewEnabled = value) }
    sync()
}

// 1.133：震动开关（渠道级，改动后重建渠道）
internal fun NotificationSettingsViewModel.setVibrationEnabled(value: Boolean) {
    val context = getApplication<Application>()
    if (_uiState.value.vibrationEnabled == value) return
    updateLocal { it.copy(vibrationEnabled = value) }
    com.maodouchat.notification.NotificationPreferences.setVibrationEnabled(context, value)
    com.maodouchat.notification.NotificationInfrastructure.ensureChannels(context)
    sync()
}

internal fun NotificationSettingsViewModel.setRingtoneEnabled(value: Boolean) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.RINGTONE)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.ringtone_disabled)) }
        return
    }
    if (_uiState.value.ringtoneEnabled == value) return
    updateLocal { it.copy(ringtoneEnabled = value) }
    sync()
}

internal fun NotificationSettingsViewModel.setTaskRemindersEnabled(value: Boolean) {
    val context = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.TASK_REMINDERS)) {
        _uiState.update { it.copy(infoMessage = context.getString(R.string.task_reminders_disabled)) }
        return
    }
    if (_uiState.value.taskRemindersEnabled == value) return
    _uiState.update {
        it.copy(
            taskRemindersEnabled = value,
            errorMessage = null,
            infoMessage = null,
        )
    }
    saveLocal(_uiState.value)
    AiTaskReminderPreferences.setTaskRemindersEnabled(app, value)
    updateReminderScheduling(_uiState.value.enableNotifications)
    // Turning reminders off must drop already-posted AI trays (WorkManager cancel
    // alone leaves shade entries until user swipes). Center AI rows stay for history;
    // tray cancel matches open-AiTasks / leave-chat cleanup.
    if (!value) {
        com.maodouchat.notification.ReminderNotificationService.cancelAllAiTaskReminders(app)
    }
    _uiState.update { it.copy(infoMessage = text(R.string.notifications_task_reminders_saved)) }
}
