package com.maodouchat.ui.screen.chatdetail

// 定时发送 / 稍后提醒的对话框簇：从 ChatDetailScheduleComponents 拆出的同包对话框（定时发送/稍后提醒时间选择）。


import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.OnSurface

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

