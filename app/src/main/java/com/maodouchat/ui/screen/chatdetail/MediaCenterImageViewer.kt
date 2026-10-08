package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.ui.component.ZoomableAsyncImage
import com.maodouchat.util.RuntimeFlags

@Composable
internal fun MediaCenterImageViewer(
    message: Message,
    onDismiss: () -> Unit,
    secretChatId: String? = null,
    currentUserId: String? = null,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
    val secretPayload = remember(secretChatId, currentUserId) {
        if (secretChatId.isNullOrBlank() || !RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) null
        else {
            val dh = com.maodouchat.watermark.DeviceHint.androidId(context)
            com.maodouchat.watermark.FrequencyWatermark.buildPayload(currentUserId, secretChatId, dh)
        }
    }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            ZoomableAsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = context,
                    data = message.parsedContent(),
                    secretPayload = secretPayload,
                ),
                contentDescription = stringResource(R.string.chat_fullscreen_image),
                onSingleTap = onDismiss
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close), tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.media_viewer_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onSave) {
                        Text(stringResource(R.string.common_save), color = Color.White)
                    }
                    TextButton(onClick = onShare) {
                        Text(stringResource(R.string.common_share), color = Color.White)
                    }
                }
            }
        }
    }
}
