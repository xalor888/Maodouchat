package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 聊天锁相关对话框（G185 从 ChatDetailRoute 的 chatLockBlocking 分支抽出）。
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
