package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 待发送的媒体（G79 从 `ChatDetailRoute.kt` 连同预览对话框一起搬来）。
 *
 * 图片与视频预览共用同一结构：`viewOnce`（阅后即焚）与 `spoiler`（剧透模糊）是两个
 * 跨预览/发送的开关，因此随 pending 一起传递，而不是拆成三个平行参数。
 */
internal data class PendingImageSend(
    val uri: android.net.Uri,
    val viewOnce: Boolean,
    val spoiler: Boolean
)

/**
 * 发送前预览确认（G79 从 `ChatDetailRoute.kt` 拆出，原 84 行 = 图片 47 + 视频 37）。
 *
 * 两个 `@Composable`：图片预览（8.43，选图 → 预览 → 发送/取消，含「重新选择」）与
 * 视频预览（0.69，文件信息 + 发送/取消；此前点即发，误选无法挽回）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param pending 待发送的媒体（含 uri / viewOnce / spoiler）
 * @param onDismiss 取消（原 `pendingImageConfirm = null` / `pendingVideoConfirm = null`）
 * @param onRechoose 图片预览里的「重新选择」：回到相册（原 `pendingViewOnce/pendingSpoiler` + picker launch）
 */
@Composable
internal fun ImageSendPreviewDialog(
    pending: PendingImageSend,
    onDismiss: () -> Unit,
    onRechoose: (viewOnce: Boolean, spoiler: Boolean) -> Unit,
    onSendImage: (PendingImageSend) -> Unit,
    onSendSpoilerImage: (PendingImageSend) -> Unit,
    onSendViewOnceImage: (PendingImageSend) -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_image_send_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                coil.compose.AsyncImage(
                    model = pending.uri,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                // 0.71：预览后可选「重新选择」（误选不用取消再进一次相册）
                TextButton(
                    onClick = { onRechoose(pending.viewOnce, pending.spoiler) },
                    modifier = Modifier.align(Alignment.End)
                ) { Text(stringResource(R.string.chat_rechoose), color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                when {
                    pending.viewOnce -> onSendViewOnceImage(pending)
                    pending.spoiler -> onSendSpoilerImage(pending)
                    else -> onSendImage(pending)
                }
            }) { Text(stringResource(R.string.chat_send), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
        }
    )}

/**
 * 视频发送前预览（文件信息 + 发送/取消，与图片流程一致）。
 */
@Composable
internal fun VideoSendPreviewDialog(
    pending: PendingImageSend,
    onDismiss: () -> Unit,
    onSendVideo: (PendingImageSend) -> Unit,
    onSendSpoilerVideo: (PendingImageSend) -> Unit,
    onSendViewOnceVideo: (PendingImageSend) -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_video_send_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp).padding(bottom = 8.dp)
                )
                val fileName = pending.uri.lastPathSegment?.substringAfterLast('/')
                    ?: stringResource(R.string.message_preview_video)
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                when {
                    pending.viewOnce -> onSendViewOnceVideo(pending)
                    pending.spoiler -> onSendSpoilerVideo(pending)
                    else -> onSendVideo(pending)
                }
            }) { Text(stringResource(R.string.chat_send), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
        }
    )}
