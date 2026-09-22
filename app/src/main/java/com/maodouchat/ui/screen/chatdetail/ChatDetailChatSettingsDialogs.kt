package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.UnreadRed

/**
 * 会话设置类对话框（G83 从 `ChatDetailRoute.kt` 拆出，原 125 行 = 45 + 35 + 45）。
 *
 * 三个 `@Composable`：静音至（稍后提醒时段）、解除聊天锁、联系人操作
 * （查看资料 / 屏蔽或解除 / 举报）。三者都是「会话级设置」的原子操作，合成一个文件。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * 各自内含的判定：
 * - 静音至：1h / 8h / 24h 三档；**已有生效静音时才显示「一键取消」**；
 * - 解除锁：PIN 只留数字、最长 8 位；
 * - 联系人：屏蔽进行中**禁用按钮**（防重复点击），并按当前屏蔽态切换文案与颜色。
 */

/** 静音至对话框。 */
@Composable
internal fun ChatSilentUntilDialog(
    chatId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val silentUntilSetTip = stringResource(R.string.chat_silent_until_set)
    val silentUntilClearedTip = stringResource(R.string.chat_silent_until_cleared)
    // 9.219：捕获局部 chatId（同免打扰段，回调延迟执行防会话删除竞态）
    val chatIdForSilent = chatId
    val hasActiveSilent = com.maodouchat.notification.ChatQuietHoursStore.silentUntil(context, chatIdForSilent) > System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.chat_silent_until_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                listOf(
                    1L to R.string.chat_silent_until_1h,
                    8L to R.string.chat_silent_until_8h,
                    24L to R.string.chat_silent_until_24h
                ).forEach { (hours, labelRes) ->
                    TextButton(
                        onClick = {
                            com.maodouchat.notification.ChatQuietHoursStore.setSilentUntil(
                                context,
                                chatIdForSilent,
                                System.currentTimeMillis() + hours * 3600_000L
                            )
                            onDismiss()
                            Toast.makeText(context, silentUntilSetTip, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurface) }
                }
                // 1.41：已有生效静音时可一键取消
                if (hasActiveSilent) {
                    TextButton(
                        onClick = {
                            com.maodouchat.notification.ChatQuietHoursStore.setSilentUntil(context, chatIdForSilent, 0L)
                            onDismiss()
                            Toast.makeText(context, silentUntilClearedTip, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_silent_until_clear), color = LocalChatPalette.current.unreadRed) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
        }
    )}

/** 解除聊天锁对话框（输 PIN 解除）。 */
@Composable
internal fun ChatDisableChatLockDialog(
    pinDraft: String,
    contactDisplayName: String,
    onPinDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemoveLock: (String) -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.chat_lock_disable_title)) },
        text = {
            OutlinedTextField(
                value = pinDraft,
                onValueChange = { raw ->
                    onPinDraftChange(raw.filter { it.isDigit() }.take(8))
                },
                label = { Text(stringResource(R.string.chat_lock_enter_pin, contactDisplayName)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onRemoveLock(pinDraft)
                onDismiss()
                onPinDraftChange("")
            }) {
                Text(stringResource(R.string.chat_lock_menu_disable), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )}

/** 联系人操作对话框（查看资料 / 屏蔽或解除 / 举报）。 */
@Composable
internal fun ChatContactActionsDialog(
    contactDisplayName: String,
    isContactBlocked: Boolean,
    isBlockingContact: Boolean,
    isGroup: Boolean,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
    onToggleBlock: () -> Unit,
    onReport: () -> Unit,
) {

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(contactDisplayName.ifBlank { stringResource(R.string.chat_contact) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(if (isContactBlocked) R.string.chat_blocked_description else R.string.chat_block_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onViewProfile()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.chat_view_profile), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    enabled = !isBlockingContact,
                    onClick = {
                        onToggleBlock()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBlockingContact) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(if (isContactBlocked) R.string.chat_unblock_user else R.string.chat_block_user), color = if (isContactBlocked) Primary else UnreadRed)
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onReport()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.chat_report_user), color = LocalChatPalette.current.unreadRed)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.common_done)) } }
    )}
