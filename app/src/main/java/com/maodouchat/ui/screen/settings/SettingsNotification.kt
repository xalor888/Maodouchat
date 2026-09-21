package com.maodouchat.ui.screen.settings

import com.maodouchat.notification.NotificationInfrastructure
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.security.findActivity
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.maodouchat.R
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip

/**
 * 「新消息通知」页（G101 从 `SettingsSubScreens.kt` 拆出，原 336 行）。
 *
 * 当前使用 DataStore / SharedPreferences 控制 App 通知行为。
 * 含它专属的 `DndTimeRow`（免打扰时段选择）与 `formatDndTime`（分钟数 → 本地化时间）。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）。
 * 纯搬移，不改判断。
 */

/**
 * 「新消息通知」页 — 当前使用 DataStore / SharedPreferences 控制 App 内通知设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 部分资源字符串在回调内读取，lint 无法区分；组合作用域内已用 stringResource
fun NotificationSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: NotificationSettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDndDialog by remember { mutableStateOf(false) }
    // 9.3xx：后台推送保活模式选择
    val context = LocalContext.current
    val ringtoneDefault = stringResource(R.string.notifications_ringtone_default)
    // 8.48：通知铃声选择（RingtoneManager picker；空 = 系统默认）
    var ringtoneUri by remember { mutableStateOf(com.maodouchat.notification.NotificationPreferences.ringtoneUri(context)) }
    val ringtoneTitle = remember(ringtoneUri) {
        ringtoneUri?.let { uri ->
            runCatching {
                android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))?.getTitle(context)
            }.getOrNull()
        } ?: ringtoneDefault
    }
    val ringtonePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
        ringtoneUri = uri
        com.maodouchat.notification.NotificationPreferences.setRingtoneUri(context, uri)
        com.maodouchat.notification.NotificationInfrastructure.ensureChannels(context)
    }

    // 0.72：群聊独立通知铃声
    var groupRingtoneUri by remember { mutableStateOf(com.maodouchat.notification.NotificationPreferences.groupRingtoneUri(context)) }
    val groupRingtoneTitle = remember(groupRingtoneUri) {
        groupRingtoneUri?.let { uri ->
            runCatching {
                android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))?.getTitle(context)
            }.getOrNull()
        } ?: ringtoneDefault
    }
    val groupRingtonePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
        groupRingtoneUri = uri
        com.maodouchat.notification.NotificationPreferences.setGroupRingtoneUri(context, uri)
        com.maodouchat.notification.NotificationInfrastructure.ensureChannels(context)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(8.dp))
            if (state.isLoading || state.isSaving || state.errorMessage != null || state.infoMessage != null) {
                Text(
                    text = when {
                        state.isLoading -> stringResource(R.string.notifications_syncing)
                        state.isSaving -> stringResource(R.string.notifications_saving)
                        state.errorMessage != null -> state.errorMessage
                        else -> state.infoMessage
                    } ?: "",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else LocalChatPalette.current.textSecondary
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                val pushSubtitle = when {
                    state.pushReady -> stringResource(R.string.notifications_push_ready)
                    state.pushConfigured -> stringResource(R.string.notifications_push_configured_not_ready)
                    else -> stringResource(R.string.notifications_push_missing)
                }
                ActionRow(
                    label = stringResource(R.string.notifications_push_channel_title),
                    subtitle = pushSubtitle + "\n" + stringResource(R.string.notifications_push_vendor_note),
                    onClick = { viewModel.refreshPushStatus() }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_enable_title),
                    subtitle = stringResource(R.string.notifications_enable_subtitle),
                    checked = state.enableNotifications,
                    onCheckedChange = { viewModel.setEnableNotifications(it) }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_sound_title),
                    subtitle = stringResource(R.string.notifications_sound_subtitle),
                    checked = state.soundEnabled,
                    onCheckedChange = { viewModel.setSoundEnabled(it) },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.133：震动开关（渠道级）
                SwitchRow(
                    title = stringResource(R.string.notifications_vibration_title),
                    subtitle = stringResource(R.string.notifications_vibration_subtitle),
                    checked = state.vibrationEnabled,
                    onCheckedChange = { viewModel.setVibrationEnabled(it) },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.119：发送测试通知（验证铃声/震动设置生效）
                ActionRow(
                    label = stringResource(R.string.notifications_test_title),
                    subtitle = stringResource(R.string.notifications_test_subtitle),
                    enabled = state.enableNotifications
                ) {
                    com.maodouchat.notification.MessageNotificationService.showTestNotification(context)
                    Toast.makeText(context, context.getString(R.string.notifications_test_sent), Toast.LENGTH_SHORT).show()
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(
                    label = stringResource(R.string.notifications_ringtone_title),
                    subtitle = ringtoneTitle,
                    enabled = state.enableNotifications
                ) {
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.notifications_ringtone_title))
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        ringtoneUri?.let { putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(it)) }
                    }
                    ringtonePicker.launch(intent)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 0.72：群聊独立铃声（单独选择器，空 = 回退单聊铃声）
                ActionRow(
                    label = stringResource(R.string.notifications_group_ringtone_title),
                    subtitle = groupRingtoneTitle,
                    enabled = state.enableNotifications
                ) {
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.notifications_group_ringtone_title))
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        groupRingtoneUri?.let { putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(it)) }
                    }
                    groupRingtonePicker.launch(intent)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_preview_title),
                    subtitle = stringResource(R.string.notifications_preview_subtitle),
                    checked = state.previewEnabled,
                    onCheckedChange = { viewModel.setPreviewEnabled(it) },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_task_reminders_title),
                    subtitle = stringResource(R.string.notifications_task_reminders_subtitle),
                    checked = state.taskRemindersEnabled,
                    onCheckedChange = { viewModel.setTaskRemindersEnabled(it) },
                    enabled = state.enableNotifications
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                // 9.3xx：移除与上方铃声选择器同名的冗余行（无可见开关、点击无反馈的"重复开关"）
                SwitchRow(
                    title = stringResource(R.string.notifications_dnd_schedule_title),
                    subtitle = stringResource(R.string.notifications_dnd_schedule_subtitle),
                    checked = state.dndEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setDndSchedule(enabled, state.dndStartMinute, state.dndEndMinute)
                    },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(
                    label = stringResource(R.string.notifications_dnd_title),
                    subtitle = if (state.dndEnabled) {
                        stringResource(
                            R.string.notifications_dnd_schedule_active,
                            formatDndTime(state.dndStartMinute),
                            formatDndTime(state.dndEndMinute)
                        )
                    } else {
                        stringResource(R.string.notifications_dnd_schedule_off)
                    },
                    enabled = state.enableNotifications,
                    onClick = { showDndDialog = true }
                )
            }
        }

        if (showDndDialog) {
            // 9.3xx：弹窗内不再重复放"启用"开关（页面已有同一开关，弹窗只负责选时间段），
            // 避免同屏出现两个一模一样的开关。
            var startMinute by remember(state.dndStartMinute) { mutableIntStateOf(state.dndStartMinute) }
            var endMinute by remember(state.dndEndMinute) { mutableIntStateOf(state.dndEndMinute) }
            val activity = LocalContext.current.findActivity()
            val dndPresets = listOf(
                Triple(22 * 60, 7 * 60, R.string.notifications_dnd_preset_night),
                Triple(12 * 60, 14 * 60, R.string.notifications_dnd_preset_noon),
                Triple(0, 8 * 60, R.string.notifications_dnd_preset_sleep),
                Triple(18 * 60, 9 * 60, R.string.notifications_dnd_preset_evening),
                Triple(9 * 60, 18 * 60, R.string.notifications_dnd_preset_workday),
                Triple(14 * 60, 17 * 60, R.string.notifications_dnd_preset_focus),
                Triple(8 * 60, 12 * 60, R.string.notifications_dnd_preset_morning),
                Triple(13 * 60, 15 * 60, R.string.notifications_dnd_preset_siesta),
                Triple(21 * 60, 23 * 60, R.string.notifications_dnd_preset_late_evening),
                Triple(23 * 60, 6 * 60, R.string.notifications_dnd_preset_deep_night),
                Triple(5 * 60, 9 * 60, R.string.notifications_dnd_preset_early_morning)
            )
            AlertDialog(
                onDismissRequest = { showDndDialog = false },
                title = { Text(stringResource(R.string.notifications_dnd_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.notifications_dnd_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        Text(stringResource(R.string.notifications_dnd_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dndPresets.forEach { (start, end, labelRes) ->
                                FilterChip(
                                    selected = startMinute == start && endMinute == end,
                                    onClick = {
                                        startMinute = start
                                        endMinute = end
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                        DndTimeRow(
                            label = stringResource(R.string.notifications_dnd_start_time),
                            minute = startMinute,
                            enabled = state.dndEnabled,
                            onPick = {
                                if (activity != null) {
                                    android.app.TimePickerDialog(
                                        activity,
                                        { _, h, m -> startMinute = h * 60 + m },
                                        startMinute / 60,
                                        startMinute % 60,
                                        true
                                    ).show()
                                }
                            }
                        )
                        DndTimeRow(
                            label = stringResource(R.string.notifications_dnd_end_time),
                            minute = endMinute,
                            enabled = state.dndEnabled,
                            onPick = {
                                if (activity != null) {
                                    android.app.TimePickerDialog(
                                        activity,
                                        { _, h, m -> endMinute = h * 60 + m },
                                        endMinute / 60,
                                        endMinute % 60,
                                        true
                                    ).show()
                                }
                            }
                        )
                        Text(
                            stringResource(R.string.notifications_dnd_overnight_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setDndSchedule(state.dndEnabled, startMinute, endMinute)
                        showDndDialog = false
                    }) { Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = { TextButton(onClick = { showDndDialog = false }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
            )
        }
    }
}

@Composable
private fun DndTimeRow(label: String, minute: Int, enabled: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(formatDndTime(minute), style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
    }
}

private fun formatDndTime(minuteOfDay: Int): String {
    val safe = minuteOfDay.coerceIn(0, 1439)
    val h = safe / 60
    val m = safe % 60
    return "%02d:%02d".format(h, m)
}

/**
 * 标题 + 副标题 + 开关的一行（通知/通用/AI 三页共用）。
 */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalChatPalette.current.textHint
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
