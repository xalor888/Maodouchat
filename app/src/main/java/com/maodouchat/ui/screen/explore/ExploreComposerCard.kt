package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.maodouchat.R
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 「发现」发布卡片簇（从 ExploreComposerCards.kt 按簇拆出）。
 *
 * 两个声明：`ComposerCard`（完整发布卡片）+ `VisibilitySelector`（可见范围选择器）。
 * 纯搬移，不改判断。
 */

@Composable
internal fun ComposerCard(
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
