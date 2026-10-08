package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/** 会话详情的「安全确认」类对话框：忘记聊天锁、清空本地历史、开启密聊。消息管理与群组/通话类对话框已按专题拆出。 */

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
