package com.maodouchat.ui.screen.settings

import com.maodouchat.data.repository.NotificationSettingsNetworkRepository
import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.notification.NotificationInfrastructure
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiTaskReminderPreferences
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.network.NotificationSettingsRequest
import com.maodouchat.network.NotificationSettingsResponse
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「新消息通知」设置页的 ViewModel（G119 从 `SettingsSubViewModels.kt` 拆出，原 465 行）。
 *
 * 管通知总开关、声音/震动/预览/铃声、任务提醒、免打扰时段（显式开关 + 分钟级窗口）、
 * 厂商推送（FCM）配置与就绪状态刷新。含它的 UI 状态数据类 `NotificationSettingsUiState`。
 *
 * **拆解约束**：不直接抓应用级数据库单例；网络经 `data/repository` 的薄仓库（G328c 起
 * 不再直连 `ApiService`），偏好经
 * `NotificationPreferences` / `AiTaskReminderPreferences`。纯搬移，不改判断。
 */

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
    private val app = application
    private val tokenManager = TokenManager.getInstance(application)
    private val syncMutex = Mutex()
    private var settingsRevision = 0L
    private var lastSyncedRevision = 0L
    private var syncGeneration = 0L
    private var refreshGeneration = 0L
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var revisionOwnerUserId: String? = tokenManager.getUserId()

    private val _uiState = MutableStateFlow(NotificationSettingsUiState(
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
        pushConfigured = !tokenManager.getToken().isNullOrBlank(),
        pushReady = (application as? com.maodouchat.MaodouchatApp)?.realtimeEventDispatcher?.connectionState?.value == com.maodouchat.core.realtime.RealtimeConnectionState.CONNECTED
    ))
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun isRealtimeConnected(): Boolean {
        val app = getApplication<Application>() as? com.maodouchat.MaodouchatApp
        return app?.realtimeEventDispatcher?.connectionState?.value == com.maodouchat.core.realtime.RealtimeConnectionState.CONNECTED
    }

    private fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    // 8.34 修复：sync 失败持久化标记——上次会话有未同步的本地修改时，新 ViewModel 的
    // 0/0 计数会令 refresh 把服务端旧值静默覆盖本地新值（设置无声回滚）。置 revision 使
    // refresh 的「无本地变更」守卫失效，保留本地值直至下次成功 sync 清除标记。
    init {
        if (NotificationPreferences.hasPendingSync(application)) {
            settingsRevision = 1L
            lastSyncedRevision = 0L
        }
    }

    private fun adoptRevisionOwner(ownerUserId: String) {
        if (revisionOwnerUserId == ownerUserId) return
        revisionOwnerUserId = ownerUserId
        settingsRevision = 0L
        lastSyncedRevision = 0L
        syncGeneration++
    }

    init {
        refresh()
    }

    fun refreshPushStatus() {
        _uiState.update {
            it.copy(
                pushConfigured = !tokenManager.getToken().isNullOrBlank(),
                pushReady = isRealtimeConnected()
            )
        }
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        val token = tokenManager.getToken()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        refreshPushStatus()
        if (token.isNullOrBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired)) }
            return
        }
        adoptRevisionOwner(ownerUserId)
        val revisionAtStart = settingsRevision
        if (!isCurrentOwner(ownerUserId)) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        val job = viewModelScope.launch {
            try {
                if (!isCurrentOwner(ownerUserId)) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                NotificationSettingsNetworkRepository().settings(liveToken).fold(
                    onSuccess = { remote ->
                        if (refreshGeneration != generation || !isCurrentOwner(ownerUserId)) {
                            return@fold
                        }
                        if (settingsRevision == revisionAtStart && revisionAtStart <= lastSyncedRevision) {
                            saveLocal(remote)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    enableNotifications = remote.enableNotifications,
                                    soundEnabled = remote.soundEnabled,
                                    previewEnabled = remote.previewEnabled,
                                    ringtoneEnabled = remote.ringtoneEnabled,
                                    dndStartHour = remote.dndStartHour.coerceIn(0, 23),
                                    dndEndHour = remote.dndEndHour.coerceIn(0, 23),
                                    dndEnabled = remote.dndEnabled,
                                    dndStartMinute = remote.dndStartMinute.coerceIn(0, 1439),
                                    dndEndMinute = remote.dndEndMinute.coerceIn(0, 1439),
                                    pushConfigured = !tokenManager.getToken().isNullOrBlank(),
                                    pushReady = isRealtimeConnected(),
                                    infoMessage = text(R.string.notifications_synced)
                                )
                            }
                            updateReminderScheduling(remote.enableNotifications)
                            if (!remote.enableNotifications) dismissPostedNotifications()
                        } else {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                    },
                    onFailure = { error ->
                        if (refreshGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.notifications_sync_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (refreshGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (refreshGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: text(R.string.notifications_sync_failed),
                        )
                    }
                }
            }
        }
        refreshJob = job
        job.invokeOnCompletion {
            if (refreshJob === job) refreshJob = null
        }
    }

    fun setEnableNotifications(value: Boolean) {
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

    fun setSoundEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_SOUND)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.notification_sound_disabled)) }
            return
        }
        if (_uiState.value.soundEnabled == value) return
        updateLocal { it.copy(soundEnabled = value) }
        sync()
    }

    fun setPreviewEnabled(value: Boolean) {
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
    fun setVibrationEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (_uiState.value.vibrationEnabled == value) return
        updateLocal { it.copy(vibrationEnabled = value) }
        com.maodouchat.notification.NotificationPreferences.setVibrationEnabled(context, value)
        com.maodouchat.notification.NotificationInfrastructure.ensureChannels(context)
        sync()
    }

    fun setRingtoneEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.RINGTONE)) {
            _uiState.update { it.copy(infoMessage = context.getString(R.string.ringtone_disabled)) }
            return
        }
        if (_uiState.value.ringtoneEnabled == value) return
        updateLocal { it.copy(ringtoneEnabled = value) }
        sync()
    }

    fun setTaskRemindersEnabled(value: Boolean) {
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

    /**
     * 设置勿扰计划：显式开关 + 分钟级窗口（0-1439）。跨天窗口 start>end 表示夜间勿扰。
     * 关闭计划（enabled=false）时窗口数据仍保留，便于下次一键恢复。
     */
    fun setDndSchedule(enabled: Boolean, startMinute: Int, endMinute: Int) {
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

    fun isDndActive(): Boolean {
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

    private fun updateLocal(transform: (NotificationSettingsUiState) -> NotificationSettingsUiState) {
        tokenManager.getUserId()?.takeIf { it.isNotBlank() }?.let(::adoptRevisionOwner)
        refreshGeneration++
        refreshJob?.cancel()
        settingsRevision++
        _uiState.update { current ->
            transform(current).copy(isLoading = false, errorMessage = null, infoMessage = null)
        }
        saveLocal(_uiState.value)
    }

    private fun sync() {
        val token = tokenManager.getToken()
        val syncOwnerUserId = tokenManager.getUserId().orEmpty()
        if (token.isNullOrBlank() || syncOwnerUserId.isBlank()) {
            syncGeneration++
            _uiState.update {
                it.copy(isSaving = false, errorMessage = text(R.string.error_session_expired))
            }
            return
        }
        adoptRevisionOwner(syncOwnerUserId)
        val generation = ++syncGeneration
        if (!isCurrentOwner(syncOwnerUserId)) return
        _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            try {
                syncMutex.withLock {
                    if (generation != syncGeneration) return@withLock
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = syncOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@withLock
                    }
                    val state = _uiState.value
                    val request = NotificationSettingsRequest(
                        enableNotifications = state.enableNotifications,
                        soundEnabled = state.soundEnabled,
                        previewEnabled = state.previewEnabled,
                        ringtoneEnabled = state.ringtoneEnabled,
                        dndStartHour = state.dndStartHour,
                        dndEndHour = state.dndEndHour,
                        dndEnabled = state.dndEnabled,
                        dndStartMinute = state.dndStartMinute,
                        dndEndMinute = state.dndEndMinute
                    )
                    val liveToken = tokenManager.getToken() ?: token
                    NotificationSettingsNetworkRepository().updateSettings(liveToken, request).fold(
                        onSuccess = { remote ->
                            if (generation != syncGeneration || !isCurrentOwner(syncOwnerUserId)) {
                                return@fold
                            }
                            lastSyncedRevision = settingsRevision
                            // 8.34：同步成功清除未同步标记（失败置位见 onFailure）
                            NotificationPreferences.markPendingSync(app, false)
                            saveLocal(remote)
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    enableNotifications = remote.enableNotifications,
                                    soundEnabled = remote.soundEnabled,
                                    previewEnabled = remote.previewEnabled,
                                    ringtoneEnabled = remote.ringtoneEnabled,
                                    dndStartHour = remote.dndStartHour.coerceIn(0, 23),
                                    dndEndHour = remote.dndEndHour.coerceIn(0, 23),
                                    dndEnabled = remote.dndEnabled,
                                    dndStartMinute = remote.dndStartMinute.coerceIn(0, 1439),
                                    dndEndMinute = remote.dndEndMinute.coerceIn(0, 1439),
                                    infoMessage = text(R.string.notifications_saved_to_account)
                                )
                            }
                            updateReminderScheduling(remote.enableNotifications)
                            if (!remote.enableNotifications) {
                                dismissPostedNotifications()
                            }
                        },
                        onFailure = { error ->
                            if (generation == syncGeneration && isCurrentOwner(syncOwnerUserId)) {
                                // 8.34：失败持久化标记——本地新值不得被后续 refresh 静默回滚
                                NotificationPreferences.markPendingSync(app, true)
                                val message = error.message ?: text(R.string.notifications_save_failed)
                                _uiState.update { it.copy(isSaving = false, errorMessage = message) }
                                _snackbar.tryEmit(message)
                            }
                        }
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (generation == syncGeneration && isCurrentOwner(syncOwnerUserId)) {
                    _uiState.update { it.copy(isSaving = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (generation == syncGeneration && isCurrentOwner(syncOwnerUserId)) {
                    // 8.34：同步异常同样置未同步标记（防刷新回滚）
                    NotificationPreferences.markPendingSync(app, true)
                    val message = error.message ?: text(R.string.notifications_save_failed)
                    _uiState.update { it.copy(isSaving = false, errorMessage = message) }
                    _snackbar.tryEmit(message)
                }
            }
        }
    }

    private fun saveLocal(state: NotificationSettingsUiState) {
        NotificationPreferences.save(
            context = app,
            enableNotifications = state.enableNotifications,
            soundEnabled = state.soundEnabled,
            previewEnabled = state.previewEnabled,
            ringtoneEnabled = state.ringtoneEnabled,
            dndStartHour = state.dndStartHour,
            dndEndHour = state.dndEndHour,
            taskRemindersEnabled = state.taskRemindersEnabled,
            dndEnabled = state.dndEnabled,
            dndStartMinute = state.dndStartMinute,
            dndEndMinute = state.dndEndMinute
        )
    }

    private fun updateReminderScheduling(notificationsEnabled: Boolean) {
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
    private fun dismissPostedNotifications() {
        com.maodouchat.notification.NotificationInfrastructure.cancelAll(app)
        com.maodouchat.notification.ReminderNotificationService.cancelAllAiTaskReminders(app)
        (app as? com.maodouchat.MaodouchatApp)?.notificationCenter?.markAllRead()
    }

    private fun saveLocal(response: NotificationSettingsResponse) {
        NotificationPreferences.save(
            context = app,
            enableNotifications = response.enableNotifications,
            soundEnabled = response.soundEnabled,
            previewEnabled = response.previewEnabled,
            ringtoneEnabled = response.ringtoneEnabled,
            dndStartHour = response.dndStartHour,
            dndEndHour = response.dndEndHour,
            dndEnabled = response.dndEnabled,
            dndStartMinute = response.dndStartMinute,
            dndEndMinute = response.dndEndMinute
        )
    }
}
