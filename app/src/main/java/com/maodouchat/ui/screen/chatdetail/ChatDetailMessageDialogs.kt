package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary

/** 会话详情的「消息管理」对话框：删除 / 编辑 / 撤回 / 发送失败重试（从 ChatDetailDialogs 按专题拆出，零行为改动）。 */

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
