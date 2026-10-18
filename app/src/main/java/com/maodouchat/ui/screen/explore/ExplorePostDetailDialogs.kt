package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 1.247：评论长按操作菜单（原 PostDetailScreen 尾部对话框；状态走参数+回调）。
 */
@Composable
internal fun PostDetailCommentMenuDialog(
    isOwn: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_comment_actions), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                TextButton(onClick = onReply, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.explore_comment_reply), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_copy), modifier = Modifier.fillMaxWidth())
                }
                if (isOwn) {
                    TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.explore_edit_comment), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.explore_delete_comment), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed)
                    }
                } else {
                    TextButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.explore_report_comment), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_close)) } }
    )
}

/**
 * 评论编辑弹窗（原 PostDetailScreen 尾部对话框；状态走参数+回调）。
 */
@Composable
internal fun PostDetailEditCommentDialog(
    text: String,
    onTextChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_edit_comment), style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text(stringResource(R.string.explore_comment_edit_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onSave
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.explore_close))
            }
        }
    )
}
