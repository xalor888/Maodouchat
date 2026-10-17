package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.MessageStatusIcon
import com.maodouchat.ui.screen.chatlist.ChatListReceiptPolicy

@Composable
internal fun MessageActionRow(
    message: Message,
    isOwnMessage: Boolean,
    isGroupChat: Boolean,
    groupReadCount: ReadCountUi?,
    onReply: (Message) -> Unit,
    onStatusClick: ((Message) -> Unit)?
) {
    val showReply = message.type in setOf(MessageType.TEXT, MessageType.MARKDOWN)
    if (!isOwnMessage && !showReply && groupReadCount == null) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(
            top = 2.dp,
            start = if (isOwnMessage) 0.dp else 44.dp,
            end = if (isOwnMessage) 14.dp else 0.dp
        )
    ) {
        if (isOwnMessage) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clickable(enabled = onStatusClick != null) {
                        onStatusClick?.invoke(message)
                    },
                contentAlignment = Alignment.Center
            ) {
                MessageStatusIcon(
                    status = ChatListReceiptPolicy.displayStatus(
                        message.status,
                        isGroup = isGroupChat
                    )
                )
            }
        }

        if (showReply) {
            TextButton(
                onClick = { onReply(message) },
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.message_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOwnMessage) {
                        com.maodouchat.ui.theme.LocalSentBubbleContentSecondary.current
                    } else {
                        com.maodouchat.ui.theme.TextHint
                    }
                )
            }
        }

        groupReadCount?.let { count ->
            val readProgress = if (count.total > 0) {
                (count.read.toFloat() / count.total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val readDescription = stringResource(
                R.string.chat_group_read_status,
                count.read,
                count.total
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .semantics { contentDescription = readDescription }
                    .clickable(enabled = onStatusClick != null) {
                        onStatusClick?.invoke(message)
                    },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { readProgress },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
