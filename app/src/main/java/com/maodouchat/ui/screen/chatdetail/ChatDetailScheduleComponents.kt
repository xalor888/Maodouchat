package com.maodouchat.ui.screen.chatdetail


/**
 * 定时发送 / 稍后提醒相关 UI（G87 从 `ChatDetailComponents.kt` 拆出，原 419 行）。
 *
 * 留守：定时横幅、定时列表（底部弹窗）两个 `@Composable`，外加时间格式化与
 * 两个本地化辅助（`scheduleRepeatLabel` 重复规则文案、`formatMuteRemaining` 禁言剩余时长）。
 * 对话框簇（定时发送/稍后提醒时间选择）已拆到同包 `ChatDetailScheduleDialogs.kt`。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.ScheduledMessage

internal fun formatScheduleTime(millis: Long): String {
    return scheduleTimeFormat.get().format(java.util.Date(millis))
}

// SimpleDateFormat 非线程安全，ThreadLocal 每线程复用一个；只调 format，不残留状态。
private val scheduleTimeFormat: ThreadLocal<java.text.SimpleDateFormat> =
    ThreadLocal.withInitial { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }

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
