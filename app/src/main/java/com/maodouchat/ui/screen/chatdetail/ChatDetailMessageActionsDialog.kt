package com.maodouchat.ui.screen.chatdetail

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.RuntimeFlags

/**
 * 消息操作弹窗（G80 从 `ChatDetailRoute.kt` 拆出，原 58 行）。
 *
 * 即「复制文本弹窗」：复制 / 转发 / 编辑 / 撤回 / 删除，以及「不可复制时给出原因」。
 *
 * 内含几条容易在后续改动中被破坏的判定，抽成独立 Composable 后可在预览里直接看：
 * - 密聊 + `SECRET_COPY_BLOCK` 开关 → 复制按钮变成「提示被阻止」而不是消失；
 * - 密聊 + `SECRET_FORWARD_BLOCK` → 不显示转发；
 * - 编辑只在**本人**、**5 分钟窗口内**、且类型为 TEXT/MARKDOWN 时出现；
 * - 撤回/删除只对本人显示；对他人只显示取消。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param onDismiss 关闭（原 `onDismiss()`）
 * @param onForward 把该消息设为待转发并加载转发目标
 * @param onEdit 进入编辑（原 `editDraft = ...; messageToEdit = msg`）
 * @param onRevoke 设为待撤回
 * @param onDelete 触发粒子删除动效
 */
@Composable
internal fun ChatDetailMessageActionsDialog(
    msg: Message,
    currentUserId: String,
    isSecretChat: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val chatCopiedMsg = stringResource(R.string.chat_copied)
    val chatClipboardMessageLabel = stringResource(R.string.chat_clipboard_message)
    val state = ChatDetailUiState(currentUserId = currentUserId, isSecretChat = isSecretChat)

    val isOwn = msg.senderId == state.currentUserId
    val withinEditWindow = System.currentTimeMillis() - msg.timestamp < 300_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_message_actions)) },
        text = { Text(msg.parsedContent()) },
        confirmButton = {
            Row {
                if (isMessageCopyable(msg.type, isSecretChat = state.isSecretChat == true, copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK))) {
                    TextButton(onClick = {
                        onCopy()
                        onDismiss()
                    }) { Text(stringResource(R.string.chat_copy)) }
                } else {
                    TextButton(onClick = {
                        Toast.makeText(context, context.getString(R.string.secret_chat_copy_blocked), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) { Text(stringResource(R.string.chat_copy)) }
                }
                if (isMessageForwardable(msg.type, isSecretChat = state.isSecretChat == true, forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK))) {
                    TextButton(onClick = { onForward() }) { Text(stringResource(R.string.chat_forward)) }
                }
                if (isOwn && withinEditWindow && (msg.type == MessageType.TEXT || msg.type == MessageType.MARKDOWN)) {
                    TextButton(onClick = { onEdit() }) { Text(stringResource(R.string.chat_edit)) }
                }
            }
        },
        dismissButton = {
            if (isOwn) {
                Row {
                    if (withinEditWindow && msg.type != MessageType.REVOKED) {
                        TextButton(onClick = { onRevoke() }) {
                            Text(stringResource(R.string.chat_revoke), color = LocalChatPalette.current.unreadRed)
                        }
                    }
                    TextButton(onClick = { onDelete() }) {
                        Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed)
                    }
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )}
