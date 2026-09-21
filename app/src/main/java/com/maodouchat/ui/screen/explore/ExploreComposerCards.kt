package com.maodouchat.ui.screen.explore

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.ui.theme.Error
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「发现」页的发布编辑器组件（G123 从 `ExploreScreen.kt` 拆出，原 461 行）。
 *
 * 四个声明：`CompactComposer`（底部紧凑发布条）、`ComposerCard`（完整发布卡片，
 * 文字 + 图片九宫格 + 可见范围）、`VisibilitySelector`（可见范围选择器）、
 * `ImageGrid`（1/2/3/4/6/9 宫格自适应布局）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
internal fun CompactComposer(
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    text: String,
    imageDrafts: List<PostImageDraft>,
    visibilityOptions: List<VisibilityOption>,
    selectedVisibility: String,
    isPublishing: Boolean,
    canPublish: Boolean,
    visibilityReady: Boolean,
    onTextChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onPasteImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onRetryImage: (String) -> Unit,
    onVisibilitySelected: (String) -> Unit,
    onClear: () -> Unit,
    onPublish: () -> Unit,
) {
    if (!expanded) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = null,
                tint = LocalChatPalette.current.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.explore_share_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalChatPalette.current.textHint,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.AddPhotoAlternate,
                contentDescription = stringResource(R.string.explore_add_images, 0),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.explore_share_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCollapse) {
                Text(stringResource(R.string.common_close), color = LocalChatPalette.current.textSecondary)
            }
        }
        ComposerCard(
            text = text,
            imageDrafts = imageDrafts,
            visibilityOptions = visibilityOptions,
            selectedVisibility = selectedVisibility,
            isPublishing = isPublishing,
            canPublish = canPublish,
            visibilityReady = visibilityReady,
            onTextChange = onTextChange,
            onPickImages = onPickImages,
            onPasteImages = onPasteImages,
            onRemoveImage = onRemoveImage,
            onRetryImage = onRetryImage,
            onVisibilitySelected = onVisibilitySelected,
            onClear = onClear,
            onPublish = onPublish
        )
    }
}

@Composable
private fun ComposerCard(
    text: String,
    imageDrafts: List<PostImageDraft>,
    visibilityOptions: List<VisibilityOption>,
    selectedVisibility: String,
    isPublishing: Boolean,
    canPublish: Boolean,
    visibilityReady: Boolean = true,
    onTextChange: (String) -> Unit,
    onPickImages: () -> Unit,
    // 1.158：从剪贴板粘贴图片
    onPasteImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    // 1.211：重试失败的上传图片
    onRetryImage: (String) -> Unit = {},
    onVisibilitySelected: (String) -> Unit,
    // 1.202：清空发布框
    onClear: () -> Unit = {},
    onPublish: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (text.isNotBlank() || imageDrafts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.explore_composer_clear), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // 1.207：点击已选图片预览大图
            var previewDraftUri by remember { mutableStateOf<String?>(null) }
            if (previewDraftUri != null) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { previewDraftUri = null },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.95f))
                            .clickable { previewDraftUri = null },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = OwnerScopedImageKeys.request(
                                context = androidx.compose.ui.platform.LocalContext.current,
                                data = previewDraftUri,
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) onTextChange(it) },
                placeholder = { Text(stringResource(R.string.explore_share_placeholder)) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                supportingText = {
                    Text(
                        "${text.length}/2000",
                        color = if (text.length > 1800) Error else LocalChatPalette.current.textSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
            VisibilitySelector(
                options = visibilityOptions,
                selectedVisibility = selectedVisibility,
                onVisibilitySelected = onVisibilitySelected
            )
            if (imageDrafts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageDrafts.forEachIndexed { index, draft ->
                        Box(modifier = Modifier.size(82.dp)) {
                            AsyncImage(
                                model = OwnerScopedImageKeys.request(
                                    context = androidx.compose.ui.platform.LocalContext.current,
                                    data = draft.uri,
                                ),
                                contentDescription = stringResource(R.string.explore_selected_image),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    // 1.207：点击预览大图
                                    .then(if (!draft.isUploading) Modifier.clickable { previewDraftUri = draft.uri.toString() } else Modifier)
                            )
                            if (draft.isUploading || draft.errorMessage != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.42f))
                                ) {
                                    if (draft.isUploading) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        // 1.211：上传失败 → 点击重试
                                        IconButton(onClick = { onRetryImage(draft.id) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.explore_retry_upload), tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = { onRemoveImage(draft.id) },
                                modifier = Modifier.align(Alignment.TopEnd).size(28.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.explore_remove), tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onPickImages,
                    enabled = imageDrafts.size < 9 && !isPublishing,
                    label = { Text(stringResource(R.string.explore_add_images, imageDrafts.size)) },
                    leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                // 1.158：从剪贴板粘贴图片
                AssistChip(
                    onClick = onPasteImages,
                    enabled = imageDrafts.size < 9 && !isPublishing,
                    label = { Text(stringResource(R.string.explore_paste_images)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = onPublish, enabled = canPublish) {
                    if (isPublishing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.explore_publish))
                }
            }
            if (!visibilityReady && !isPublishing) {
                Text(
                    stringResource(R.string.explore_visibility_load_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = Error
                )
            }
        }
    }
}

@Composable
private fun VisibilitySelector(
    options: List<VisibilityOption>,
    selectedVisibility: String,
    onVisibilitySelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.explore_visibility_title), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selectedVisibility == option.value,
                    onClick = { onVisibilitySelected(option.value) },
                    label = { Text(visibilityOptionLabel(option.value)) }
                )
            }
        }
    }
}

// 1.94：动态图片网格（Explore 与 PostDetail 共用；点击全屏查看）
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun ImageGrid(imageUrls: List<String>) {
    val columns = when (imageUrls.size) {
        1 -> 1
        2, 4 -> 2
        else -> 3
    }
    var galleryIndex by remember { mutableStateOf<Int?>(null) }
    // 1.129：保存图片时的下载状态（按钮转圈防重复点击）
    var savingImage by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        imageUrls.forEachIndexed { index, url ->
            // 单图时用更宽的比例展示，多图保持方格
            val eachWeight = 1f / columns
            AsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = androidx.compose.ui.platform.LocalContext.current,
                    data = url,
                    sizeWidth = 300,
                    sizeHeight = 300,
                ),
                contentDescription = stringResource(R.string.explore_post_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .then(if (imageUrls.size == 1) Modifier.fillMaxWidth(0.7f) else Modifier.fillMaxWidth(eachWeight))
                    .aspectRatio(if (imageUrls.size == 1) 16f / 9f else 1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { galleryIndex = index }
            )
        }
    }

    // 全屏图片查看器：多图可左右切换，index 经 policy 夹紧
    galleryIndex?.let { rawIndex ->
        val index = ExploreFeedPolicy.clampImageIndex(rawIndex, imageUrls.size)
        val imageUrl = imageUrls.getOrNull(index) ?: return@let
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { galleryIndex = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                // 1.143：全屏图片支持捏合缩放/双击放大（复用聊天 ZoomableAsyncImage）
                com.maodouchat.ui.component.ZoomableAsyncImage(
                    model = OwnerScopedImageKeys.request(
                        context = androidx.compose.ui.platform.LocalContext.current,
                        data = imageUrl,
                    ),
                    contentDescription = stringResource(R.string.explore_fullscreen_image),
                    modifier = Modifier.fillMaxSize()
                )
                if (imageUrls.size > 1) {
                    Text(
                        text = stringResource(R.string.explore_image_index, index + 1, imageUrls.size),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 20.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    if (index > 0) {
                        IconButton(
                            onClick = { galleryIndex = index - 1 },
                            modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.explore_image_prev),
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    if (index < imageUrls.lastIndex) {
                        IconButton(
                            onClick = { galleryIndex = index + 1 },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.explore_image_next),
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { galleryIndex = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.explore_close), tint = Color.White, modifier = Modifier.size(28.dp))
                }
                // 1.127：保存动态图片到相册（认证下载 + MediaStore）；1.129：下载中转圈
                val viewerContext = androidx.compose.ui.platform.LocalContext.current
                val saveScope = androidx.compose.runtime.rememberCoroutineScope()
                IconButton(
                    onClick = {
                        if (savingImage) return@IconButton
                        savingImage = true
                        saveScope.launch {
                            try {
                                val token = com.maodouchat.network.TokenManager.getInstance(viewerContext).getToken().orEmpty()
                                if (token.isBlank()) {
                                    android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val file = java.io.File(viewerContext.cacheDir, "post_${System.currentTimeMillis()}.jpg")
                                ApiService.downloadPostImage(token, imageUrl, file).fold(
                                    onSuccess = {
                                        val saved = com.maodouchat.util.MediaExport.saveToGallery(
                                            viewerContext,
                                            android.net.Uri.fromFile(file).toString(),
                                            "image/jpeg",
                                            "maodouchat-post-${System.currentTimeMillis()}.jpg"
                                        )
                                        android.widget.Toast.makeText(
                                            viewerContext,
                                            if (saved) R.string.explore_saved_to_gallery else R.string.explore_save_failed,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onFailure = {
                                        android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } finally {
                                savingImage = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) {
                    if (savingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.explore_save_image), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                // 1.136：分享动态图片（认证下载 → 系统分享）
                var sharingImage by remember { mutableStateOf(false) }
                IconButton(
                    onClick = {
                        if (sharingImage) return@IconButton
                        sharingImage = true
                        saveScope.launch {
                            try {
                                val token = com.maodouchat.network.TokenManager.getInstance(viewerContext).getToken().orEmpty()
                                if (token.isBlank()) return@launch
                                val file = java.io.File(viewerContext.cacheDir, "post_share_${System.currentTimeMillis()}.jpg")
                                ApiService.downloadPostImage(token, imageUrl, file).fold(
                                    onSuccess = {
                                        val shared = com.maodouchat.util.MediaExport.share(
                                            viewerContext,
                                            android.net.Uri.fromFile(file).toString(),
                                            "image/jpeg",
                                            viewerContext.getString(R.string.chat_share)
                                        )
                                        if (!shared) {
                                            android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onFailure = {
                                        android.widget.Toast.makeText(viewerContext, R.string.explore_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } finally {
                                sharingImage = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 64.dp)
                ) {
                    if (sharingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.chat_share), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun visibilityOptionLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private)
    else -> stringResource(R.string.explore_visibility_public)
}
