package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
