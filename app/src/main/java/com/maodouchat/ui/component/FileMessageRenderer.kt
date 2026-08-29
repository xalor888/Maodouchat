package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.UnreadRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File renderer driven only by mapped presentation data and UI callbacks. */
@Composable
internal fun FileMessageRenderer(
    message: Message,
    presentation: MessagePresentation,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    senderName: String? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    onFileClick: ((Message) -> Unit)? = null,
    transferProgress: Float? = null,
    transferState: String? = null,
    transferError: String? = null,
    onPauseTransfer: ((String) -> Unit)? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
    currentUserId: String? = null,
    onReactionClick: ((String) -> Unit)? = null,
    onStatusClick: ((Message) -> Unit)? = null,
    showStatusIcon: Boolean = true,
) {
    val palette = LocalChatPalette.current
    val file = presentation.file
    val fileName = file?.name ?: stringResource(R.string.message_file)
    val fileDetails = remember(file, presentation.timestamp) {
        val mime = file?.mimeType
            ?.substringAfterLast('/')
            ?.uppercase()
            ?.takeIf { it.isNotBlank() && it != "OCTET-STREAM" }
        val size = file?.sizeBytes?.takeIf { it > 0 }?.let(::formatPresentationFileSize)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(presentation.timestamp))
        listOfNotNull(mime, size, time).joinToString(" · ")
    }
    val transferStatus = transferState?.let { fileTransferStatusText(it, transferError) }
    val controlTint = if (isOwnMessage) Color.White else Primary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) androidx.compose.foundation.layout.Arrangement.End else androidx.compose.foundation.layout.Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isOwnMessage) {
            if (showAvatar) {
                Avatar(name = senderName ?: "?", size = AvatarSize.SM, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
        }

        Column(horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .widthIn(max = 260.dp)
                    .clip(if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent else com.maodouchat.ui.theme.LocalBubbleShapes.current.received)
                    .background(if (isOwnMessage) LocalChatBubbleColor.current else palette.chatBubbleReceived)
                    .then(
                        if (!isOwnMessage) Modifier.border(
                            1.dp,
                            palette.chatBubbleReceivedBorder,
                            com.maodouchat.ui.theme.LocalBubbleShapes.current.received,
                        ) else Modifier,
                    )
                    .then(if (onFileClick != null && transferState == null) Modifier.clickable { onFileClick(message) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .background(if (isOwnMessage) Color.White else Primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = stringResource(R.string.message_file),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            fileDetails,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        transferStatus?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (transferState == AttachmentTransferState.FAILED) {
                                    if (isOwnMessage) Color.White else UnreadRed
                                } else if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        transferProgress?.let { progress ->
                            Spacer(modifier = Modifier.height(5.dp))
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = if (isOwnMessage) Color.White else Primary,
                                trackColor = if (isOwnMessage) Color.White.copy(alpha = 0.25f) else Primary.copy(alpha = 0.14f),
                            )
                        }
                        FileTransferActions(
                            messageId = presentation.id,
                            state = transferState,
                            tint = controlTint,
                            onPause = onPauseTransfer,
                            onResume = onResumeTransfer,
                            onCancel = onCancelTransfer,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        DisappearCountdownLabel(expiresAt = presentation.expiresAt, isOwnMessage = isOwnMessage)
                        if (isOwnMessage && showStatusIcon) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.clickable(enabled = onStatusClick != null) { onStatusClick?.invoke(message) }) {
                                MessageStatusIcon(presentation.status)
                            }
                        }
                    }
                }
            }
            ReactionSummaryRow(message, currentUserId, isOwnMessage, onReactionClick)
        }
    }
}

@Composable
private fun FileTransferActions(
    messageId: String,
    state: String?,
    tint: Color,
    onPause: ((String) -> Unit)?,
    onResume: ((String) -> Unit)?,
    onCancel: ((String) -> Unit)?,
) {
    when (state) {
        AttachmentTransferState.QUEUED, AttachmentTransferState.UPLOADING -> {
            Row(modifier = Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPause?.invoke(messageId) }, modifier = Modifier.size(32.dp), enabled = onPause != null) {
                    Icon(Icons.Default.Pause, stringResource(R.string.chat_file_transfer_pause), tint = tint, modifier = Modifier.size(18.dp))
                }
                CancelTransferButton(messageId, tint, onCancel)
            }
        }
        AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED -> {
            Row(modifier = Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onResume?.invoke(messageId) }, modifier = Modifier.size(32.dp), enabled = onResume != null) {
                    Icon(
                        if (state == AttachmentTransferState.FAILED) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        stringResource(if (state == AttachmentTransferState.FAILED) R.string.chat_file_transfer_retry else R.string.chat_file_transfer_resume),
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                CancelTransferButton(messageId, tint, onCancel)
            }
        }
    }
}

@Composable
private fun CancelTransferButton(messageId: String, tint: Color, onCancel: ((String) -> Unit)?) {
    IconButton(onClick = { onCancel?.invoke(messageId) }, modifier = Modifier.size(32.dp), enabled = onCancel != null) {
        Icon(Icons.Default.Close, stringResource(R.string.chat_file_transfer_cancel), tint = tint, modifier = Modifier.size(18.dp))
    }
}

private fun formatPresentationFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
