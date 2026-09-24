package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.ScheduledMessage

/**
 * 定时发送 / 稍后提醒相关 UI（G87 从 `ChatDetailComponents.kt` 拆出，原 419 行）。
 *
 * 四个 `@Composable`：定时横幅、定时列表（底部弹窗）、定时发送对话框、稍后提醒时间选择；
 * 外加两个本地化辅助：`scheduleRepeatLabel`（重复规则文案）与 `formatMuteRemaining`（禁言剩余时长）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

internal fun formatScheduleTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

@Composable
internal fun ScheduledMessagesBanner(
    items: List<com.maodouchat.util.ScheduledMessage>,
    onCancel: (String) -> Unit,
    onReschedule: (String) -> Unit = {},
    onViewAll: () -> Unit = {}
) {
    val context = LocalContext.current
    val preview = items.take(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .clickable(onClick = onViewAll)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.schedule_pending_title, items.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (items.size > 3) {
                Text(
                    text = stringResource(R.string.schedule_view_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        preview.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatScheduleTime(item.sendAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    // 1.15：重复定时消息显示重复标识（1.21：含次数上限）
                                scheduleRepeatLabel(context, item.repeatIntervalMs, item.repeatCount, item.occurrencesSent, item.weekdaysOnly)?.let { repeatLabel ->
                        Text(
                            text = repeatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                TextButton(onClick = { onReschedule(item.id) }) {
                    Text(stringResource(R.string.schedule_reschedule), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { onCancel(item.id) }) {
                    Text(stringResource(R.string.schedule_cancel_one), color = LocalChatPalette.current.unreadRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledMessagesListSheet(
    items: List<com.maodouchat.util.ScheduledMessage>,
    onCancel: (String) -> Unit,
    onReschedule: (String) -> Unit,
    onSendNow: (String) -> Unit,
    onCancelAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 1.174：全部取消确认
    var showCancelAllConfirm by rememberSaveable { mutableStateOf(false) }
    val filteredItems = remember(items, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            items
        } else {
            items.filter { it.text.contains(query, ignoreCase = true) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.schedule_pending_title, items.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // 1.174：全部取消
            if (items.isNotEmpty()) {
                TextButton(onClick = { showCancelAllConfirm = true }) {
                    Text(stringResource(R.string.schedule_cancel_all), color = LocalChatPalette.current.unreadRed)
                }
            }
            if (items.size >= 4) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(120) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.schedule_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
            if (filteredItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_search_empty),
                    color = LocalChatPalette.current.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filteredItems.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatScheduleTime(item.sendAtMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                                // 1.15：重复定时消息显示重复标识（1.21：含次数上限）
                    scheduleRepeatLabel(context, item.repeatIntervalMs, item.repeatCount, item.occurrencesSent, item.weekdaysOnly)?.let { repeatLabel ->
                                    Text(
                                        text = repeatLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            TextButton(onClick = { onReschedule(item.id) }) {
                                Text(stringResource(R.string.schedule_reschedule), color = MaterialTheme.colorScheme.primary)
                            }
                            // 1.168：立即发送
                            TextButton(onClick = { onSendNow(item.id) }) {
                                Text(stringResource(R.string.schedule_send_now), color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { onCancel(item.id) }) {
                                Text(stringResource(R.string.schedule_cancel_one), color = LocalChatPalette.current.unreadRed)
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_close))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 1.174：全部取消确认
    if (showCancelAllConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelAllConfirm = false },
            title = { Text(stringResource(R.string.schedule_cancel_all)) },
            text = { Text(stringResource(R.string.schedule_cancel_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAllConfirm = false
                    onCancelAll()
                }) { Text(stringResource(R.string.chat_clear_history_yes), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun ScheduleSendDialog(
    onPickDelay: (Long) -> Unit,
    onPickAt: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    titleRes: Int = R.string.schedule_title,
    // 1.07：重复定时（间隔 + 1.21 可选次数 + 1.62 工作日）
    onPickRepeat: (Long, Int, Boolean) -> Unit = { _, _, _ -> },
    /** 1.43：重排时允许编辑文案（onTextEdited 非空时显示文本输入框）。 */
    initialText: String = "",
    onTextEdited: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    // 1.21：重复次数选择（0=不限）
    var repeatCountChoice by remember { mutableIntStateOf(0) }
    // 1.43：重排文案草稿
    var textDraft by remember(initialText) { mutableStateOf(initialText) }
    val options = com.maodouchat.util.ScheduledMessagePolicy.QUICK_DELAYS_MS.zip(
        listOf(
            R.string.schedule_delay_1m,
            R.string.schedule_delay_5m,
            R.string.schedule_delay_15m,
            R.string.schedule_delay_30m,
            R.string.schedule_delay_1h,
            R.string.schedule_delay_2h,
            R.string.schedule_delay_3h,
            R.string.schedule_delay_4h,
            R.string.schedule_delay_6h,
            R.string.schedule_delay_12h,
            R.string.schedule_delay_24h,
            R.string.schedule_delay_2d,
            R.string.schedule_delay_3d,
            R.string.schedule_delay_7d
        )
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 1.43：重排时编辑文案
                if (onTextEdited != null) {
                    OutlinedTextField(
                        value = textDraft,
                        onValueChange = {
                            textDraft = it.take(com.maodouchat.util.ScheduledMessagePolicy.MAX_TEXT_LENGTH)
                            onTextEdited(textDraft)
                        },
                        placeholder = { Text(stringResource(R.string.schedule_edit_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                options.forEach { (delay, label) ->
                    TextButton(
                        onClick = { onPickDelay(delay) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(label), color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(
                    onClick = {
                        openScheduleDateTimePicker(
                            context = context,
                            onPicked = onPickAt,
                            onTooSoon = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.schedule_custom_too_soon),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onTooLate = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.schedule_custom_too_late),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.schedule_custom), color = MaterialTheme.colorScheme.primary)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.schedule_repeat_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                // 1.07：重复定时（每日/每周）；1.21：应用所选次数；1.62：工作日重复
                TextButton(
                    onClick = { onPickRepeat(24L * 3600_000L, repeatCountChoice, false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_daily), color = MaterialTheme.colorScheme.primary) }
                TextButton(
                    onClick = { onPickRepeat(7L * 24 * 3600_000L, repeatCountChoice, false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_weekly), color = MaterialTheme.colorScheme.primary) }
                TextButton(
                    onClick = { onPickRepeat(24L * 3600_000L, repeatCountChoice, true) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_weekdays), color = MaterialTheme.colorScheme.primary) }
                Text(
                    stringResource(R.string.schedule_repeat_count_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(3, 7, 30, 0).forEach { count ->
                        TextButton(
                            onClick = { repeatCountChoice = count },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (count > 0) pluralStringResource(R.plurals.schedule_repeat_count, count, count)
                                else stringResource(R.string.schedule_repeat_count_unlimited),
                                color = if (repeatCountChoice == count) Primary else OnSurface,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 消息「稍后提醒」时间选择：复用定时发送档位文案，窗口 1 分钟 ~ 30 天。 */
@Composable
internal fun MessageReminderTimeDialog(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_reminder_menu), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            val delays = com.maodouchat.util.MessageReminderPolicy.QUICK_DELAYS_MS
            val labels = listOf(
                R.string.schedule_delay_1m, R.string.schedule_delay_5m, R.string.schedule_delay_15m,
                R.string.schedule_delay_30m, R.string.schedule_delay_1h, R.string.schedule_delay_2h,
                R.string.schedule_delay_3h, R.string.schedule_delay_4h, R.string.schedule_delay_6h,
                R.string.schedule_delay_12h, R.string.schedule_delay_24h, R.string.schedule_delay_2d,
                R.string.schedule_delay_3d, R.string.schedule_delay_7d
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                delays.zip(labels).forEach { (delayMs, labelRes) ->
                    TextButton(
                        onClick = { onPick(delayMs) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 1.15：定时消息重复间隔 → 展示文案（0=一次性返回 null 不显示）。1.21/1.48：含次数与剩余次数。1.62：工作日重复。 */
internal fun scheduleRepeatLabel(context: android.content.Context, intervalMs: Long, repeatCount: Int, occurrencesSent: Int, weekdaysOnly: Boolean): String? {
    val base = when {
        weekdaysOnly -> context.getString(R.string.schedule_repeat_weekdays)
        intervalMs == 24L * 3600_000L -> context.getString(R.string.schedule_repeat_daily)
        intervalMs == 7L * 24 * 3600_000L -> context.getString(R.string.schedule_repeat_weekly)
        else -> if (intervalMs <= 0L) return null else context.getString(R.string.schedule_repeat_badge)
    }
    return if (repeatCount > 0) {
        val remaining = (repeatCount - occurrencesSent).coerceAtLeast(0)
        "$base · ${context.resources.getQuantityString(R.plurals.schedule_repeat_remaining, remaining, remaining)}"
    } else base
}

/** 8.48：禁言剩余时长（分钟/小时/天）本地化显示。 */
internal fun formatMuteRemaining(context: android.content.Context, remainingMs: Long): String {
    val minutes = remainingMs / 60_000L
    return when {
        minutes <= 0L -> context.getString(R.string.time_just_now)
        minutes < 60L -> context.getString(R.string.chat_mute_minutes, minutes)
        minutes < 24 * 60L -> context.getString(R.string.chat_mute_hours, minutes / 60L)
        else -> context.getString(R.string.chat_mute_days, minutes / (24 * 60L))
    }
}
