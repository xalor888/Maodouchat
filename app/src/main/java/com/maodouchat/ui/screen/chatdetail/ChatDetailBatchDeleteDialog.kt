package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.core.model.Message
import com.maodouchat.domain.messaging.MessageBatchDeletePolicy
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 批量删除确认对话框（G86 从 `ChatDetailRoute.kt` 拆出，原 50 行）。
 *
 * 四条判定在 :domain:messaging 的 `MessageBatchDeletePolicy`，此处只留对话框接线：
 * 1. **仅删除本人消息**（8.53）——服务端 403 只能删自己发送的，选中他人消息不参与删除，
 *    仅作转发/星标用途，因此要把「跳过几条他人的」明确告知用户；
 * 2. **批量封顶 60 条**（8.55）——服务端 mutation 限流 60/min，超出必须提示分批，
 *    否则一次点下去会 429「删一半剩一半」；
 * 3. 无可删项时确认按钮**禁用**（不能点一个必然无效的按钮）；
 * 4. 删除完成给 Toast 提示（1.50），且复数形式随条数变化。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param selectedMessages 多选模式选中的消息
 * @param currentUserId 当前用户（用于过滤「仅本人」）
 * @param onDelete 串行批量删除（原 `onDelete(ids)`，9.229）
 * @param onDismiss 关闭（原 `onDismiss()`）
 */
@Composable
internal fun ChatDetailBatchDeleteDialog(
    selectedMessages: List<Message>,
    currentUserId: String,
    onDelete: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onSelectionCleared: () -> Unit,
) {
    val context = LocalContext.current

    val batch = remember(selectedMessages, currentUserId) {
        MessageBatchDeletePolicy.resolve(selectedMessages, currentUserId)
    }
    val batchDeleteDoneTip = pluralStringResource(R.plurals.chat_batch_delete_done, batch.cappedCount)
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.chat_delete_selected_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chat_delete_selected_message, batch.cappedCount))
                if (batch.skippedCount > 0) {
                    Text(
                        stringResource(R.string.chat_batch_delete_skipped_others, batch.skippedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (batch.cappedOut > 0) {
                    Text(
                        stringResource(R.string.chat_batch_delete_capped, batch.cappedOut),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = batch.canConfirm, onClick = {
                // 9.229：串行批量删除，避免逐条并发打满服务端 mutation 限流
                onDelete(batch.cappedIds)
                onSelectionCleared()
                onDismiss()
                // 1.50：删除完成提示
                Toast.makeText(context, batchDeleteDoneTip, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed) }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
        }
    )}
