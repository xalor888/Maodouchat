package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState

@Composable
internal fun AttachmentTransferOverlay(
    messageId: String,
    state: String,
    errorCode: String?,
    progress: Float?,
    onPause: ((String) -> Unit)?,
    onResume: ((String) -> Unit)?,
    onCancel: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 132.dp, max = 176.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = fileTransferStatusText(state, errorCode),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { (progress ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.24f)
        )
        Row(modifier = Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                AttachmentTransferState.QUEUED, AttachmentTransferState.UPLOADING -> {
                    IconButton(onClick = { onPause?.invoke(messageId) }, enabled = onPause != null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Pause, stringResource(R.string.chat_file_transfer_pause), tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED -> {
                    IconButton(onClick = { onResume?.invoke(messageId) }, enabled = onResume != null, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (state == AttachmentTransferState.FAILED) Icons.Default.Refresh else Icons.Default.PlayArrow,
                            stringResource(if (state == AttachmentTransferState.FAILED) R.string.chat_file_transfer_retry else R.string.chat_file_transfer_resume),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                else -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            if (state in setOf(
                    AttachmentTransferState.PREPARING,
                    AttachmentTransferState.QUEUED,
                    AttachmentTransferState.UPLOADING,
                    AttachmentTransferState.PAUSED,
                    AttachmentTransferState.FAILED
                )
            ) {
                IconButton(onClick = { onCancel?.invoke(messageId) }, enabled = onCancel != null, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.chat_file_transfer_cancel), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun fileTransferStatusText(state: String, errorCode: String?): String = when (state) {
    AttachmentTransferState.PREPARING -> stringResource(R.string.chat_file_transfer_preparing)
    AttachmentTransferState.QUEUED -> stringResource(R.string.chat_file_transfer_queued)
    AttachmentTransferState.UPLOADING -> stringResource(R.string.chat_file_transfer_uploading)
    AttachmentTransferState.READY -> stringResource(R.string.chat_file_transfer_ready)
    AttachmentTransferState.SENDING -> stringResource(R.string.chat_file_transfer_sending)
    AttachmentTransferState.PAUSED -> stringResource(R.string.chat_file_transfer_paused)
    AttachmentTransferState.FAILED -> when {
        errorCode == "SOURCE_MISSING" -> stringResource(R.string.chat_file_transfer_source_missing)
        errorCode?.startsWith("NETWORK_") == true -> stringResource(R.string.chat_file_transfer_network_failed)
        errorCode?.startsWith("TIMEOUT_") == true -> stringResource(R.string.chat_file_transfer_timeout)
        errorCode?.startsWith("SEND_") == true -> stringResource(R.string.chat_file_transfer_send_failed)
        else -> stringResource(R.string.chat_file_transfer_failed)
    }
    else -> stringResource(R.string.chat_file_transfer_queued)
}
