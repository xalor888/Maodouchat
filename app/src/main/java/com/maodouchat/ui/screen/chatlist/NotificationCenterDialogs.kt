package com.maodouchat.ui.screen.chatlist

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem

/** 通知中心对话框簇：清空全部确认 / 清除会话通知 / 长按操作菜单（从 NotificationCenterScreen 尾部纯搬移）。 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun NotificationCenterDialogs(
    showClearConfirm: Boolean,
    clearChatId: String?,
    notifyMenuFor: Pair<NotificationCenterItem, String?>?,
    viewModel: NotificationCenterViewModel,
    onShowClearConfirmChange: (Boolean) -> Unit,
    onClearChatIdChange: (String?) -> Unit,
    onNotifyMenuForChange: (Pair<NotificationCenterItem, String?>?) -> Unit
) {
    val context = LocalContext.current

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { onShowClearConfirmChange(false) },
            title = { Text(stringResource(R.string.notif_center_clear_all)) },
            text = { Text(stringResource(R.string.notif_center_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); onShowClearConfirmChange(false) }) {
                    Text(stringResource(R.string.notif_center_clear_confirm_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowClearConfirmChange(false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 1.170：清除某个会话的全部通知
    clearChatId?.let { chatId ->
        AlertDialog(
            onDismissRequest = { onClearChatIdChange(null) },
            title = { Text(stringResource(R.string.notif_center_clear_chat_title)) },
            text = { Text(stringResource(R.string.notif_center_clear_chat_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeChat(chatId)
                    onClearChatIdChange(null)
                }) {
                    Text(stringResource(R.string.notif_center_clear_chat_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onClearChatIdChange(null) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 1.268：长按操作菜单（复制通知内容 / 清除该会话通知）
    notifyMenuFor?.let { (item, chatId) ->
        AlertDialog(
            onDismissRequest = { onNotifyMenuForChange(null) },
            title = { Text(stringResource(R.string.notif_center_actions)) },
            text = {
                Column {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val copyText = buildString {
                            append(item.title)
                            if (item.preview?.isNotBlank() == true) {
                                if (isNotEmpty()) append("\n")
                                append(item.preview)
                            }
                        }
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.notif_center_actions), copyText))
                        Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                        onNotifyMenuForChange(null)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_copy), modifier = Modifier.fillMaxWidth())
                    }
                    if (!chatId.isNullOrBlank()) {
                        TextButton(onClick = {
                            onNotifyMenuForChange(null)
                            onClearChatIdChange(chatId)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.notif_center_clear_chat_title), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (item.read) {
                        TextButton(onClick = {
                            viewModel.markUnread(item.id, item)
                            onNotifyMenuForChange(null)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.notif_center_mark_unread), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { onNotifyMenuForChange(null) }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}
