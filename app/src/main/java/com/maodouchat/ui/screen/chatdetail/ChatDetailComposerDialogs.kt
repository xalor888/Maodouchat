package com.maodouchat.ui.screen.chatdetail


import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.util.DisappearingMessagePolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 输入栏周边的贴纸、杂项对话框与选择器。（从 ChatDetailComposerExtras 拆出） */

@Composable
internal fun stickerPackLabel(nameKey: String): String = when (nameKey) {
    "mood" -> stringResource(R.string.sticker_pack_mood)
    "gesture" -> stringResource(R.string.sticker_pack_gesture)
    "party" -> stringResource(R.string.sticker_pack_party)
    else -> nameKey
}



/**
 * 9.223：表情/贴纸格子项——按压缩放回弹（TG 式手感）。
 * 系统关闭动画时退化为无动效点击；无 indication（缩放即反馈）。
 */
@Composable
internal fun PressScaleGlyphItem(
    glyph: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val motion = LocalMotionSettings.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion.animationsEnabled) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "glyphPressScale"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
    }
}

@Composable
internal fun StickerPackChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Primary else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}


@Composable
internal fun ChatQuietHoursDialog(
    current: com.maodouchat.notification.ChatQuietHoursStore.QuietWindow,
    onPick: (com.maodouchat.notification.ChatQuietHoursStore.QuietWindow) -> Unit,
    onDismiss: () -> Unit
) {
    // 快捷时段：预置的常用静音窗口（start, end, labelRes）
    val presets = listOf(
        Triple(22 * 60, 7 * 60, R.string.chat_quiet_hours_night),
        Triple(12 * 60, 14 * 60, R.string.chat_quiet_hours_lunch),
        Triple(23 * 60, 8 * 60, R.string.chat_quiet_hours_sleep),
        Triple(9 * 60, 18 * 60, R.string.chat_quiet_hours_workday)
    )
    val enabled = current.enabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quiet_hours_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (enabled) R.string.chat_quiet_hours_active_summary
                        else R.string.chat_quiet_hours_inactive_summary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                presets.forEach { (start, end, labelRes) ->
                    val selected = enabled && current.startMinute == start && current.endMinute == end
                    TextButton(
                        onClick = {
                            onPick(
                                com.maodouchat.notification.ChatQuietHoursStore.QuietWindow(
                                    enabled = true,
                                    startMinute = start,
                                    endMinute = end
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(stringResource(labelRes), color = if (selected) Primary else OnSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (enabled) {
                    TextButton(
                        onClick = {
                            onPick(com.maodouchat.notification.ChatQuietHoursStore.QuietWindow.OFF)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_quiet_hours_clear), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    stringResource(R.string.chat_quiet_hours_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
internal fun DisappearingMessagesDialog(
    selectedSeconds: Int,
    isUpdating: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = DisappearingMessagePolicy.ALLOWED_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disappear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.disappear_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Text(
                    text = stringResource(R.string.disappear_limit_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { seconds ->
                    val selected = seconds == selectedSeconds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isUpdating) { onSelect(seconds) }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (seconds == 0) Icons.Outlined.Schedule else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = if (selected) Primary else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = disappearSecondsLabel(seconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Primary else OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun disappearSecondsLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.disappear_off)
    30 -> stringResource(R.string.disappear_30s)
    60 -> stringResource(R.string.disappear_1m)
    2 * 60 -> stringResource(R.string.disappear_2m)
    5 * 60 -> stringResource(R.string.disappear_5m)
    15 * 60 -> stringResource(R.string.disappear_15m)
    60 * 60 -> stringResource(R.string.disappear_1h)
    2 * 60 * 60 -> stringResource(R.string.disappear_2h)
    4 * 60 * 60 -> stringResource(R.string.disappear_4h)
    8 * 60 * 60 -> stringResource(R.string.disappear_8h)
    12 * 60 * 60 -> stringResource(R.string.disappear_12h)
    24 * 60 * 60 -> stringResource(R.string.disappear_24h)
    7 * 24 * 60 * 60 -> stringResource(R.string.disappear_7d)
    30 * 24 * 60 * 60 -> stringResource(R.string.disappear_30d)
    else -> stringResource(R.string.disappear_off)
}


@Composable
internal fun pinnedPreviewText(message: Message?): String {
    if (message == null) return stringResource(R.string.chat_pinned_preview_generic)
    return when (MessagePinPolicy.previewKind(message.type)) {
        MessagePinPolicy.PreviewKind.TEXT -> {
            val text = MessagePinPolicy.textPreview(message.content)
            if (text.isBlank()) stringResource(R.string.chat_pinned_preview_generic) else text
        }
        MessagePinPolicy.PreviewKind.IMAGE -> stringResource(R.string.chat_pinned_preview_image)
        MessagePinPolicy.PreviewKind.VOICE -> stringResource(R.string.chat_pinned_preview_voice)
        MessagePinPolicy.PreviewKind.VIDEO -> stringResource(R.string.chat_pinned_preview_video)
        MessagePinPolicy.PreviewKind.FILE -> stringResource(R.string.chat_pinned_preview_file)
        MessagePinPolicy.PreviewKind.LOCATION -> stringResource(R.string.chat_pinned_preview_location)
        MessagePinPolicy.PreviewKind.STICKER -> stringResource(R.string.chat_pinned_preview_sticker)
        MessagePinPolicy.PreviewKind.GENERIC -> stringResource(R.string.chat_pinned_preview_generic)
    }
}

@Composable
internal fun ReportDialog(
    title: String,
    onDismiss: () -> Unit,
    onReport: (reason: String, description: String?) -> Unit
) {
    val reasons = stringArrayResource(R.array.chat_report_reasons).toList()
    // 8.49 防御：资源数组为空时回退空串（此前 reasons.first() 依赖资源不被清空）
    var selectedReason by rememberSaveable { mutableStateOf(reasons.firstOrNull().orEmpty()) }
    var description by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reasons.forEach { reason ->
                    val selected = reason == selectedReason
                    TextButton(
                        onClick = { selectedReason = reason },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (selected) PrimaryFixed.copy(alpha = 0.42f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(
                            text = reason,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selected) Primary else OnSurface
                        )
                    }
                }
                TextField(
                    value = description,
                    onValueChange = { description = it.take(800) },
                    placeholder = { Text(stringResource(R.string.chat_report_description), color = LocalChatPalette.current.textHint) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onReport(selectedReason, description.trim().takeIf { it.isNotBlank() })
                }
            ) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * 日历跳转对话框：选日期后跳到该日第一条消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateJumpDialog(
    onDismiss: () -> Unit,
    onJump: (dayStartMillis: Long) -> Unit
) {
    val context = LocalContext.current
    val today = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_jump_date_title)) },
        text = {
            DatePicker(state = datePickerState, showModeToggle = false)
        },
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val utcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                    // 选中的是 UTC 当天 00:00；换算成本地时区当天 00:00
                    val localStart = java.time.Instant.ofEpochMilli(utcMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    onJump(localStart)
                }
            ) { Text(stringResource(R.string.chat_jump_date_jump)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
        }
    )
}

/**
 * System date → time picker; enforces [ScheduledMessagePolicy] min/max window before [onPicked].
 */
internal fun openScheduleDateTimePicker(
    context: Context,
    onPicked: (Long) -> Unit,
    onTooSoon: () -> Unit,
    onTooLate: () -> Unit
) {
    val now = java.util.Calendar.getInstance()
    val minCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS
    }
    val maxCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MAX_DELAY_MS
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            android.app.TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year)
                        set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    when {
                        picked < minCal.timeInMillis -> onTooSoon()
                        picked > maxCal.timeInMillis -> onTooLate()
                        else -> onPicked(picked)
                    }
                },
                minCal.get(java.util.Calendar.HOUR_OF_DAY),
                minCal.get(java.util.Calendar.MINUTE),
                true
            ).show()
        },
        minCal.get(java.util.Calendar.YEAR),
        minCal.get(java.util.Calendar.MONTH),
        minCal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply {
        datePicker.minDate = minCal.timeInMillis
        datePicker.maxDate = maxCal.timeInMillis
    }.show()
}

@Composable
internal fun ReactionPickerRow(onPick: (String) -> Unit) {
    val reactions = remember {
        listOf(
            "\uD83D\uDC4D", // 👍
            "\uD83D\uDC4E", // 👎
            "\u2764\uFE0F", // ❤️
            "\uD83D\uDE02", // 😂
            "\uD83D\uDE0D", // 😍
            "\uD83D\uDE2E", // 😮
            "\uD83D\uDE22", // 😢
            "\uD83D\uDE21", // 😠
            "\uD83D\uDD25", // 🔥
            "\uD83C\uDF89", // 🎉
            "\uD83D\uDC4F", // 👏
            "\uD83D\uDE4F", // 🙏
            "\uD83D\uDC40", // 👀
            "\uD83E\uDD14", // 🤔
            "\uD83D\uDCAF", // 💯
            "\u2705",      // ✅
            "\uD83D\uDE80", // 🚀
            "\u2B50",      // ⭐
            "\uD83C\uDF1F", // 🌟
            "\uD83E\uDD73", // 🥳
            "\uD83E\uDD70", // 🥰
            "\uD83D\uDCAA", // 💪
            "\uD83E\uDD1D", // 🤝
            "\uD83D\uDE0A", // 😊
            "\uD83D\uDE4C", // 🙌
            "\uD83E\uDD29", // 🤩
            "\uD83E\uDD72", // 🥲
            "\uD83E\uDD23", // 🤣
            "\uD83D\uDC4C", // 👌
            "\uD83E\uDEF6"  // 🫶
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp)
    ) {
        reactions.forEach { emoji ->
            TextButton(
                onClick = { onPick(emoji) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(emoji, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

