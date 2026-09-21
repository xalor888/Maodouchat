package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import com.maodouchat.util.RuntimeFlags
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.util.MediaCache
import com.maodouchat.util.MediaExport
import com.maodouchat.util.MediaViewerPolicy

/**
 * 全屏媒体查看器（G76 从 `ChatDetailRoute.kt` 拆出，原 207 行 = 图片 116 + 视频 91）。
 *
 * 两个 `@Composable`：全屏图片（缩放 + 保存/分享）与全屏视频（播放 + 进度 + 保存/分享）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param onDismiss 关闭查看器（原 `onDismiss()` / `onDismiss()`）
 */
@Composable
internal fun FullscreenImageDialog(
    msg: Message,
    isSecretChat: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val meta = remember(msg.id, msg.content) { msg.parsedMeta() }
        val mime = com.maodouchat.util.MediaViewerPolicy.defaultMime(msg.type.name, meta.fileMimeType)
        val displayName = com.maodouchat.util.MediaViewerPolicy.defaultFileName(
            msg.type.name,
            meta.fileName,
            mime
        )
        val localOk = remember(msg.content) {
            com.maodouchat.util.MediaCache.isReadableLocalUri(context, msg.parsedContent())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            com.maodouchat.ui.component.ZoomableAsyncImage(
                model = msg.parsedContent(),
                contentDescription = stringResource(R.string.chat_fullscreen_image),
                onSingleTap = { onDismiss() }
            )
            IconButton(
                onClick = { onDismiss() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_close), tint = Color.White, modifier = Modifier.size(32.dp))
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
                    TextButton(
                        onClick = {
                            if (isSecretChat == true) {
                                Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            if (!localOk || !com.maodouchat.util.MediaViewerPolicy.canExportLocal(
                                    localOk,
                                    secretChat = isSecretChat == true,
                                    exportBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)
                                )) {
                                Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    com.maodouchat.util.MediaExport.saveToGallery(
                                        context = context,
                                        rawUri = msg.parsedContent(),
                                        mimeType = mime,
                                        displayName = displayName
                                    )
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(if (ok) R.string.media_export_saved else R.string.media_export_save_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.common_save), color = Color.White)
                    }
                    TextButton(
                        onClick = {
                            if (isSecretChat == true) {
                                Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            if (!localOk || !com.maodouchat.util.MediaViewerPolicy.canShareLocal(
                                    localOk,
                                    secretChat = isSecretChat == true,
                                    exportBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)
                                )) {
                                Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val ok = com.maodouchat.util.MediaExport.share(
                                context = context,
                                rawUri = msg.parsedContent(),
                                mimeType = mime,
                                chooserTitle = context.getString(R.string.common_share)
                            )
                            if (!ok) {
                                Toast.makeText(context, context.getString(R.string.media_export_share_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.common_share), color = Color.White)
                    }
                }
            }
        }
    }}

/**
 * 全屏视频播放器（与 [FullscreenImageDialog] 同源于 `ChatDetailRoute` 的全屏媒体块）。
 */
@Composable
internal fun FullscreenVideoDialog(
    msg: Message,
    isSecretChat: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val videoContent = msg.content
    // 内容为空/非法时直接关闭弹窗，避免 Uri.parse 失败或 VideoView 加载异常
    if (videoContent.isNullOrBlank()) { onDismiss(); return }
    // 0.82：视频保存到相册所需 meta
    val videoMeta = remember(msg.id, msg.content) { msg.parsedMeta() }
    val videoMime = com.maodouchat.util.MediaViewerPolicy.defaultMime("VIDEO", videoMeta.fileMimeType)
    val videoName = com.maodouchat.util.MediaViewerPolicy.defaultFileName("VIDEO", videoMeta.fileName, videoMime)
    val videoLocalOk = remember(msg.content) {
        com.maodouchat.util.MediaCache.isReadableLocalUri(context, videoContent)
    }
    // 9.150：引用放入 remember 状态，避免内容重组时被重置为 null 导致 onDispose 跳过 stopPlayback
    val videoViewRef = remember { mutableStateOf<android.widget.VideoView?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = {
                    android.widget.VideoView(it).apply {
                        videoViewRef.value = this
                        setVideoURI(android.net.Uri.parse(videoContent))
                        setOnCompletionListener { onDismiss() }
                        setOnErrorListener { _, _, _ -> onDismiss(); true }
                        start()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    // 用户关闭/系统退出时，确保 MediaPlayer 释放，避免原生资源泄漏与后台继续出声
                    videoViewRef.value?.stopPlayback()
                    videoViewRef.value = null
                    onDismiss()
                }
            }
            IconButton(
                onClick = { onDismiss() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_close), tint = Color.White, modifier = Modifier.size(32.dp))
            }
            // 0.82：视频保存到相册
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(onClick = {
                    if (isSecretChat == true) {
                        Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (!videoLocalOk || !com.maodouchat.util.MediaViewerPolicy.canExportLocal(
                            videoLocalOk,
                            secretChat = isSecretChat == true,
                            exportBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)
                        )) {
                        Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val saved = com.maodouchat.util.MediaExport.saveToGallery(
                        context = context,
                        rawUri = videoContent,
                        mimeType = videoMime,
                        displayName = videoName
                    )
                    Toast.makeText(
                        context,
                        context.getString(if (saved) R.string.media_saved else R.string.media_save_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(R.string.media_save), color = Color.White)
                }
            }
        }
    }}
