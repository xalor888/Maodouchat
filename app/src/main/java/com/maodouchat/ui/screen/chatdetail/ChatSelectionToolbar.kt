package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun ChatSelectionToolbar(
    selectedCount: Int,
    canForward: Boolean,
    shouldStar: Boolean,
    canSelectAll: Boolean = false,
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null,
    onCancel: () -> Unit,
    onForward: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    // 1.09：批量复制选中文本
    onCopy: (() -> Unit)? = null,
    // 1.10：批量置顶选中消息（shouldPin=是否含未置顶消息，由调用方依据 pinnedMessages 计算）
    shouldPin: Boolean = false,
    onTogglePin: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.chat_messages_selected, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onSelectAll != null) {
                TextButton(enabled = canSelectAll, onClick = onSelectAll) {
                    Text(stringResource(R.string.chat_select_all_loaded))
                }
            }
            if (onClearSelection != null) {
                TextButton(enabled = selectedCount > 0, onClick = onClearSelection) {
                    Text(stringResource(R.string.chat_clear_selection))
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, stringResource(R.string.common_cancel), tint = LocalChatPalette.current.textSecondary)
            }
        }
        // 1.13：动作增多后改横向滚动，避免窄屏裁切「取消置顶」等长标签（按钮按内容自适应宽度）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            TextButton(enabled = canForward, onClick = onForward) {
                Text(stringResource(R.string.chat_forward))
            }
            if (onCopy != null) {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.chat_copy))
                }
            }
            TextButton(onClick = onToggleStar) {
                Text(stringResource(if (shouldStar) R.string.chat_star else R.string.chat_unstar))
            }
            if (onTogglePin != null) {
                TextButton(onClick = onTogglePin) {
                    Text(stringResource(if (shouldPin) R.string.chat_pin else R.string.chat_unpin))
                }
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed)
            }
        }
    }
}
