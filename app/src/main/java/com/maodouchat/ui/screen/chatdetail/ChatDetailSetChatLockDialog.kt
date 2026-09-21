package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 设置聊天锁对话框（G81 从 `ChatDetailRoute.kt` 拆出，原 71 行）。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * - PIN 只保留数字、最长 8 位（输入即过滤，不等提交再报错）；
 * - 长度必须在 `4..8`，否则给出明确错误；
 * - 两次输入必须一致；
 * - 错误信息在用户继续输入时立即清空（不留 stale 错误）；
 * - 保存成功后清空两个草稿与错误，并关闭对话框。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 四个 `rememberSaveable` 状态以「值 + setter」传入（所有权留在 Route），
 * `Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param contactDisplayName 对端显示名（用于输入框标签）
 * @param onSaved 保存成功（原 `onSaved(pin)`）
 */
@Composable
internal fun ChatDetailSetChatLockDialog(
    pinDraft: String,
    pinConfirmDraft: String,
    errorMessage: String?,
    contactDisplayName: String,
    onPinDraftChange: (String) -> Unit,
    onPinConfirmDraftChange: (String) -> Unit,
    onErrorMessageChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            onDismiss()
            onErrorMessageChange(null)
        },
        title = { Text(stringResource(R.string.chat_lock_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pinDraft,
                    onValueChange = { raw ->
                        onPinDraftChange(raw.filter { it.isDigit() }.take(8))
                        onErrorMessageChange(null)
                    },
                    label = { Text(stringResource(R.string.chat_lock_enter_pin, contactDisplayName)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pinConfirmDraft,
                    onValueChange = { raw ->
                        onPinConfirmDraftChange(raw.filter { it.isDigit() }.take(8))
                        onErrorMessageChange(null)
                    },
                    label = { Text(stringResource(R.string.chat_lock_confirm_pin)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pinDraft.length !in 4..8 ->
                        onErrorMessageChange(context.getString(R.string.chat_lock_pin_length))
                    pinDraft != pinConfirmDraft ->
                        onErrorMessageChange(context.getString(R.string.chat_lock_pin_mismatch))
                    else -> {
                        onSaved(pinDraft)
                        onDismiss()
                        onPinDraftChange("")
                        onPinConfirmDraftChange("")
                        onErrorMessageChange(null)
                    }
                }
            }) {
                Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onErrorMessageChange(null)
            }) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )}
