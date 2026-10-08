package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaGrid(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit,
    onPreview: (Message) -> Unit,
    onExportActions: (Message) -> Unit,
    secretChatId: String? = null,
    currentUserId: String? = null
) {
    val context = LocalContext.current
    val exportNeedsCache = stringResource(R.string.media_export_need_cache)
    val gridSecretPayload = remember(secretChatId, currentUserId) {
        if (secretChatId.isNullOrBlank() || !RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) null
        else {
            val dh = com.maodouchat.watermark.DeviceHint.androidId(context)
            com.maodouchat.watermark.FrequencyWatermark.buildPayload(currentUserId, secretChatId, dh)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize().padding(3.dp)
    ) {
        items(items, key = { it.message.id }, contentType = { "media_${it.message.type.name}" }) { item ->
            val message = item.message
            val localAvailable = remember(message.id, message.content) {
                MediaCache.isReadableLocalUri(context, message.parsedContent())
            }
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClick = {
                            if (!localAvailable) {
                                onOpenMessage(message.id)
                                return@combinedClickable
                            }
                            if (message.type == MessageType.VIDEO) {
                                openLocalContent(context, message.parsedContent(), message.parsedMeta().fileMimeType)
                            } else {
                                onPreview(message)
                            }
                        },
                        onLongClick = {
                            if (localAvailable) onExportActions(message)
                            else Toast.makeText(context, exportNeedsCache, Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                if (localAvailable) {
                    AsyncImage(
                        model = OwnerScopedImageKeys.request(
                            context = LocalContext.current,
                            data = message.parsedContent(),
                            secretPayload = gridSecretPayload,
                        ),
                        contentDescription = message.parsedMeta().fileName ?: stringResource(R.string.media_center_media_item),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Outlined.Image, stringResource(R.string.media_center_cache_missing), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(42.dp).align(Alignment.Center))
                }
                if (message.type == MessageType.VIDEO) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.message_preview_video), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp).align(Alignment.Center).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f), RoundedCornerShape(18.dp)).padding(5.dp))
                }
                IconButton(onClick = { onOpenMessage(message.id) }, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.media_center_open_message), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

internal fun openLocalContent(context: Context, rawUri: String, mimeType: String?) {
    runCatching {
        val parsed = rawUri.toUri()
        val uri = if (parsed.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(requireNotNull(parsed.path)))
        } else parsed
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType?.takeIf(String::isNotBlank) ?: context.contentResolver.getType(uri) ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
