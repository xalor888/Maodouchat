package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 会话详情的「确认类」对话框集合（G188 由 ChatDetailChatLockDialogs.kt 改名而来）。
 *
 * 改名前名字只涵盖「聊天锁」一类，但文件里已经有三个不同主题的确认框：
 * 忘记聊天锁密码（G185）、清空本地历史（G186）、实时位置分享时长（G187）。
 * 它们共同点是「破坏性/不可逆操作前的二次确认」，所以按这个共性命名。
 */

/**
 * 「忘记聊天锁密码？」确认框。
 *
 * 确认是**破坏性**的：会清掉本地聊天锁（`forgotChatLockAndClearLocal`），
 * 所以确认按钮用警示色、且需要二次确认。
 *
 * `visible` 为 false 时什么都不渲染——调用方不需要自己包 `if`。
 */
@Composable
internal fun ForgotChatLockConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_lock_forgot_confirm_title)) },
        text = { Text(stringResource(R.string.chat_lock_forgot_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_clear), color = LocalChatPalette.current.unreadRed)
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
 * 「清空本地聊天历史」确认框（G186 从 ChatDetailRoute 抽出）。
 *
 * 确认是**破坏性且不可恢复**的（清本地历史），所以：
 * - 确认按钮用警示色；
 * - 真正的动作由调用方在 [onConfirm] 里做——那里通常还要过
 *   `SensitiveActionGate` 的二次鉴权。鉴权逻辑留在调用方，
 *   因为它和「这个弹窗长什么样」无关。
 */
@Composable
internal fun ClearChatHistoryConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_clear_local_history)) },
        text = { Text(stringResource(R.string.chat_clear_history_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_clear), color = LocalChatPalette.current.unreadRed)
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
 * 实时位置分享时长选择（G187 从 ChatDetailRoute 抽出，28 行）。
 *
 * 三个固定时长：15 分钟 / 1 小时 / 8 小时。选中即分享，
 * 所以没有「确认」按钮——取消在 dismissButton 里。
 */
@Composable
internal fun LiveLocationDurationDialog(
    visible: Boolean,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_live_location_send)) },
        text = {
            Column {
                listOf(
                    15L * 60_000L to stringResource(R.string.live_location_duration_15m),
                    60L * 60_000L to stringResource(R.string.live_location_duration_1h),
                    8L * 60L * 60_000L to stringResource(R.string.live_location_duration_8h)
                ).forEach { (ms, label) ->
                    TextButton(
                        onClick = { onPick(ms) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * 「开启密聊」确认框（G189 从 ChatDetailRoute 抽出，24 行）。
 *
 * 密聊会改变这个会话的加密与留存行为，所以要二次确认。
 */
@Composable
internal fun SecretChatConfirmDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.secret_chat_confirm_enable_title)) },
        text = { Text(stringResource(R.string.secret_chat_confirm_enable_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_done))
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
 * 群公告全文弹窗（G190 从 ChatDetailRoute 抽出，28 行）。
 *
 * 公告可能很长，所以正文可滚动。复制是**纯 I/O**（剪贴板 + Toast），
 * 留在调用方的 [onCopy] 里——它和「这个弹窗长什么样」无关。
 */
@Composable
internal fun GroupAnnouncementDialog(
    visible: Boolean,
    announcement: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_announcement_dialog_title)) },
        text = {
            Text(
                announcement,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        // 1.301：复制公告全文（转发到别处 / 归档）
        dismissButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.group_announcement_copy), color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

/**
 * 「删除消息」确认框（G159b 从 ChatDetailRoute 抽出，36 行）。
 *
 * 自己发和自己收的消息**按钮不一样**：
 * - 自己的：红色「删除」（真正的破坏性操作，由调用方播粒子动画后删除）+ 可转发；
 * - 别人的：只有「知道了」——你并不能删掉别人的消息，只是让红点消失。
 *
 * 所有动作都留在调用方的回调里（粒子动画、转发、取消都是路由的状态）。
 */
@Composable
internal fun DeleteMessageConfirmDialog(
    visible: Boolean,
    isOwn: Boolean,
    isForwardable: Boolean,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_message_title)) },
        text = {
            Text(
                stringResource(if (isOwn) R.string.chat_delete_own_message else R.string.chat_delete_other_message)
            )
        },
        confirmButton = {
            if (isOwn) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_acknowledge)) }
            }
        },
        dismissButton = {
            if (isOwn) {
                Row {
                    if (isForwardable) {
                        TextButton(onClick = onForward) { Text(stringResource(R.string.chat_forward)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        }
    )
}

/**
 * 「编辑消息」弹窗（G160b 从 ChatDetailRoute 抽出，34 行）。
 *
 * 草稿状态（[draft] / [onDraftChange]）**由调用方持有**——`editDraft` 是
 * `by remember` 的委托状态，TextField 的 value/onValueChange 留在路由更自然
 * （编辑入口在长按菜单里，`messageToCopy` 的 onEdit 就是写它的地方）。
 * 上限 2000 字符的截断也在调用方，这里只管渲染与「空草稿不能保存」。
 */
@Composable
internal fun EditMessageDialog(
    visible: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_message)) },
        text = {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                    unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                    focusedIndicatorColor = Primary,
                    unfocusedIndicatorColor = Outline,
                    cursorColor = Primary,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = draft.trim().isNotBlank(),
                onClick = onSave
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/**
 * 「撤回消息」确认框（G161b 从 ChatDetailRoute 抽出，16 行）。
 *
 * 只有发出 **5 分钟内**的消息能撤回，所以确认按钮上带剩余分钟数。
 * 剩余分钟由 [sentAtMillis] 现算（向上取整，防显示 0 分钟）——原代码就在组合作用域里
 * 调 `System.currentTimeMillis()`，搬进来行为不变。
 */
@Composable
internal fun RevokeMessageConfirmDialog(
    visible: Boolean,
    sentAtMillis: Long,
    onRevoke: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    // 1.152：撤回剩余分钟（向上取整，防显示 0 分钟）
    val remainingMin = ((300_000L - (System.currentTimeMillis() - sentAtMillis)) / 60_000L).toInt() + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_revoke_title)) },
        text = { Text(stringResource(R.string.chat_revoke_message)) },
        confirmButton = {
            TextButton(onClick = onRevoke) {
                Text(stringResource(R.string.chat_revoke_with_limit, remainingMin), color = LocalChatPalette.current.unreadRed)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/**
 * 「发送失败」弹窗（G161b 从 ChatDetailRoute 抽出，19 行）。
 *
 * ⚠️ 这个弹窗**没有「取消」按钮**：`dismissButton` 就是「删除」（红色，走粒子动画）。
 * 原设计如此——用户看到发送失败时，除了重试就是删掉，留着没有意义。
 * 抽取时**不要**自作聪明补一个取消按钮。
 */
@Composable
internal fun RetryMessageDialog(
    visible: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_send_failed)) },
        text = { Text(stringResource(R.string.chat_send_failed_retry)) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed)
            }
        }
    )
}
