package com.maodouchat.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import java.util.Locale

@Composable
internal fun FileTransferActions(
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

internal fun formatPresentationFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
