package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 稍后提醒列表对话框（G82 从 `ChatDetailRoute.kt` 拆出，原 65 行）。
 *
 * 覆盖空态、列表 + 相对时间、单条取消（取消后本地同步移除，不等刷新）、全部清除。
 *
 * 内含一条**竞态防护**：9.219 要求捕获局部 `chatId`——回调延迟执行时 `state.chat` 可能已变空
 * （会话被删除），用构造时捕获的 id 才不会取消错会话的提醒。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 *
 * @param reminders 提醒列表（由调用方持有，删除后同步更新）
 * @param onCancelReminder 单条取消
 * @param onClearAll 清除该会话全部提醒
 */
@Composable
internal fun ChatDetailReminderListDialog(
    reminders: List<com.maodouchat.util.MessageReminderStore.MessageReminder>,
    chatId: String,
    onDismiss: () -> Unit,
    onCancelReminder: (String) -> Unit,
    onClearAll: (String) -> Unit,
    onRemindersChange: (List<com.maodouchat.util.MessageReminderStore.MessageReminder>) -> Unit,
) {
    // 9.219：调用方传入的 chatId 已是构造时捕获的局部值（防会话删除竞态）
    val reminderChatId = chatId
    var visible by remember(reminderChatId) { mutableStateOf(reminders) }
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.message_reminder_list_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            if (visible.isEmpty()) {
                Text(
                    stringResource(R.string.message_reminder_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    visible.forEach { reminder ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    reminder.messagePreview.ifBlank { stringResource(R.string.message_reminder_list_media) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    android.text.format.DateUtils.getRelativeTimeSpanString(
                                        reminder.remindAtMillis,
                                        System.currentTimeMillis(),
                                        android.text.format.DateUtils.MINUTE_IN_MILLIS
                                    ).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                            }
                            TextButton(onClick = {
                                onCancelReminder(reminder.id)
                                visible = visible.filterNot { it.id == reminder.id }
                        onRemindersChange(visible)
                            }) {
                                Text(stringResource(R.string.common_delete), color = LocalChatPalette.current.unreadRed)
                            }
                        }
                    }
                    // 1.32：清除该会话全部提醒
                    TextButton(
                        onClick = {
                            onClearAll(reminderChatId)
                            visible = emptyList()
                            onRemindersChange(visible)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.message_reminder_clear_all), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.common_done)) } }
    )}
