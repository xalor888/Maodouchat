package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

// 1.97：详情页评论输入条（支持回复目标提示条，复用 sendComment/回复状态）
@Composable
internal fun CommentComposerBar(
    text: String,
    isSending: Boolean,
    replyToComment: com.maodouchat.network.PostCommentDto?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearReply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (replyToComment != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.explore_reply_to, replyToComment.author.name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        replyToComment.content,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onClearReply) {
                    Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.explore_write_comment)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            IconButton(onClick = onSend, enabled = text.isNotBlank() && !isSending) {
                if (isSending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.explore_send), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
