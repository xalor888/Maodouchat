package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.notification.NotificationPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** 「新消息通知」设置页的 ViewModel：通知开关、任务提醒、免打扰时段与推送状态同步。 */
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

class NotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    internal val app = application
    internal val syncMutex = Mutex()
    internal var settingsRevision = 0L
    internal var lastSyncedRevision = 0L
    internal var syncGeneration = 0L
    internal var refreshGeneration = 0L
    internal var refreshJob: kotlinx.coroutines.Job? = null
    internal var revisionOwnerUserId: String? = com.maodouchat.session.CurrentSession.snapshot().userId

    internal val _uiState = MutableStateFlow(NotificationSettingsUiState(
        enableNotifications = NotificationPreferences.notificationsEnabled(application),
        soundEnabled = NotificationPreferences.soundEnabled(application),
        vibrationEnabled = NotificationPreferences.vibrationEnabled(application),
        previewEnabled = NotificationPreferences.previewEnabled(application),
        ringtoneEnabled = NotificationPreferences.ringtoneEnabled(application),
        taskRemindersEnabled = NotificationPreferences.taskRemindersEnabled(application),
        dndStartHour = NotificationPreferences.dndStartHour(application),
        dndEndHour = NotificationPreferences.dndEndHour(application),
        dndEnabled = NotificationPreferences.dndEnabled(application),
        dndStartMinute = NotificationPreferences.dndStartMinute(application),
        dndEndMinute = NotificationPreferences.dndEndMinute(application),
        pushConfigured = com.maodouchat.session.CurrentSession.hasSession(),
        pushReady = com.maodouchat.session.AppRuntime
            .realtimeDispatcherOrNull(application)
            ?.connectionState?.value == com.maodouchat.core.realtime.RealtimeConnectionState.CONNECTED
    ))
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    internal val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    // 8.34 修复：sync 失败持久化标记——上次会话有未同步的本地修改时，新 ViewModel 的
    // 0/0 计数会令 refresh 把服务端旧值静默覆盖本地新值（设置无声回滚）。置 revision 使
    // refresh 的「无本地变更」守卫失效，保留本地值直至下次成功 sync 清除标记。
    init {
        if (NotificationPreferences.hasPendingSync(application)) {
            settingsRevision = 1L
            lastSyncedRevision = 0L
        }
    }

    init {
        refresh()
    }
}
