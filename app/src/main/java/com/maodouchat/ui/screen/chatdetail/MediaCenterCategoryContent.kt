package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.ui.component.SearchHighlightAccent
import com.maodouchat.ui.component.ZoomableAsyncImage
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import java.io.File
import java.text.DateFormat
import java.util.Date

/** 媒体中心的分类内容区：按分类(图片/语音/位置/链接/文件)展示条目，
含搜索、预览、导出与各类列表。纯展示簇，数据走 ViewModel。 */
@Composable
internal fun MediaCenterCategoryContent(
    category: MediaCenterCategory,
    state: MediaCenterUiState,
    viewModel: MediaCenterViewModel,
    onOpenMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable(category) { mutableStateOf("") }
    var searchExpanded by rememberSaveable(category) { mutableStateOf(false) }
    var previewMessage by remember { mutableStateOf<Message?>(null) }
    var exportTarget by remember { mutableStateOf<Message?>(null) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val secretExportBlocked = stringResource(R.string.secret_chat_media_export_blocked)
    val exportSaved = stringResource(R.string.media_export_saved)
    val exportSavedFile = stringResource(R.string.media_export_saved_file)
    val exportSaveFailed = stringResource(R.string.media_export_save_failed)
    val shareFile = stringResource(R.string.media_center_share_file)
    val shareMedia = stringResource(R.string.common_share)
    val exportShareFailed = stringResource(R.string.media_export_share_failed)
    val hasCategoryItems = remember(state.items, category) { state.items.any { it.category == category } }

    Column(modifier = modifier) {
        if (!state.isLoading && state.isChatLocked == false && hasCategoryItems) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) searchQuery = ""
                    }
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = stringResource(
                            if (searchExpanded) R.string.chat_search_close else R.string.chat_search_action
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AnimatedVisibility(
                visible = searchExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(200) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.media_center_search_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        AnimatedContent(
            targetState = category,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mediaCenterCategory",
            modifier = Modifier.fillMaxSize().weight(1f),
        ) { selected ->
            val selectedItems = remember(state.items, selected, category, searchQuery) {
                val inCategory = state.items.filter { it.category == selected }
                val query = searchQuery.trim().takeIf { selected == category }.orEmpty()
                if (query.isBlank()) inCategory else inCategory.filter { mediaCenterItemMatches(it, query) }
            }
            if (state.isLoading || state.isChatLocked == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (selectedItems.isEmpty()) {
                if (searchQuery.isNotBlank()) MediaCenterSearchEmpty() else MediaCenterEmpty(selected)
            } else when (selected) {
                MediaCenterCategory.MEDIA -> MediaGrid(
                    items = selectedItems,
                    onOpenMessage = onOpenMessage,
                    onPreview = { previewMessage = it },
                    onExportActions = { exportTarget = it },
                    secretChatId = if (state.isSecretChat) viewModel.chatId else null,
                    currentUserId = com.maodouchat.session.CurrentSession.ownerUserId(),
                )
                MediaCenterCategory.FILES -> FileList(
                    items = selectedItems,
                    onOpenMessage = onOpenMessage,
                    onExportActions = { exportTarget = it },
                    highlightQuery = searchQuery,
                )
                MediaCenterCategory.VOICE -> VoiceList(selectedItems, onOpenMessage)
                MediaCenterCategory.LINKS -> LinkList(selectedItems, onOpenMessage, highlightQuery = searchQuery)
                MediaCenterCategory.LOCATION -> LocationList(selectedItems, onOpenMessage)
            }
        }
    }

    previewMessage?.let { msg ->
        MediaCenterImageViewer(
            message = msg,
            onDismiss = { previewMessage = null },
            secretChatId = if (state.isSecretChat) viewModel.chatId else null,
            currentUserId = com.maodouchat.session.CurrentSession.ownerUserId(),
            onSave = {
                viewModel.saveMessageMedia(msg) { res ->
                    val text = when (res) {
                        is MediaExportResult.SecretBlocked -> secretExportBlocked
                        is MediaExportResult.Success -> exportSaved
                        is MediaExportResult.Failure -> exportSaveFailed
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            },
            onShare = {
                viewModel.shareMessageMedia(msg, shareMedia) { res ->
                    val text = when (res) {
                        is MediaExportResult.SecretBlocked -> secretExportBlocked
                        is MediaExportResult.Success -> null
                        is MediaExportResult.Failure -> exportShareFailed
                    }
                    if (text != null) {
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    exportTarget?.let { msg ->
        val isFile = msg.type == MessageType.FILE
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            title = {
                Text(
                    stringResource(
                        if (isFile) R.string.media_center_files else R.string.media_center_media_item
                    )
                )
            },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.saveMessageMedia(msg) { res ->
                                val text = when (res) {
                                    is MediaExportResult.SecretBlocked -> secretExportBlocked
                                    is MediaExportResult.Success -> if (isFile) exportSavedFile else exportSaved
                                    is MediaExportResult.Failure -> exportSaveFailed
                                }
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                exportTarget = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (isFile) R.string.media_center_save_file else R.string.common_save),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.shareMessageMedia(msg, if (isFile) shareFile else shareMedia) { res ->
                                val text = when (res) {
                                    is MediaExportResult.SecretBlocked -> secretExportBlocked
                                    is MediaExportResult.Success -> null
                                    is MediaExportResult.Failure -> exportShareFailed
                                }
                                if (text != null) {
                                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                }
                                exportTarget = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (isFile) R.string.media_center_share_file else R.string.common_share),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            onOpenMessage(msg.id)
                            exportTarget = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.media_center_open_message), modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
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

@Composable
private fun MediaCenterImageViewer(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
 private fun FileList(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit,
    onExportActions: (Message) -> Unit = {},
    // 1.320：搜索关键词高亮
    highlightQuery: String = ""
) {
    val context = LocalContext.current
    val exportNeedsCache = stringResource(R.string.media_export_need_cache)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.message.id }, contentType = { "file" }) { item ->
            val message = item.message
            val meta = message.parsedMeta()
            val localAvailable = remember(message.id, message.content) { MediaCache.isReadableLocalUri(context, message.parsedContent()) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (localAvailable) openLocalContent(context, message.parsedContent(), meta.fileMimeType)
                            else onOpenMessage(message.id)
                        },
                        onLongClick = {
                            if (localAvailable) onExportActions(message)
                            else Toast.makeText(context, exportNeedsCache, Toast.LENGTH_SHORT).show()
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Description, stringResource(R.string.message_preview_file), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        // 1.320：搜索时高亮匹配文件名
                        if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(meta.fileName?.takeIf(String::isNotBlank) ?: stringResource(R.string.message_preview_file))
                        else highlightedText(meta.fileName?.takeIf(String::isNotBlank) ?: stringResource(R.string.message_preview_file), highlightQuery),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(meta.fileSizeBytes?.let(::formatBytes), formatDate(message.timestamp), if (localAvailable) stringResource(R.string.media_center_cached) else stringResource(R.string.media_center_cache_missing)).joinToString(" · "),
                        color = LocalChatPalette.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                if (localAvailable) {
                    IconButton(onClick = { onExportActions(message) }) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.media_center_share_file), tint = LocalChatPalette.current.textSecondary)
                    }
                }
                IconButton(onClick = { onOpenMessage(message.id) }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.media_center_open_message), tint = LocalChatPalette.current.textSecondary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
private fun VoiceList(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit
) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.message.id }, contentType = { "voice" }) { item ->
            val message = item.message
            val meta = message.parsedMeta()
            val localAvailable = remember(message.id, message.content) {
                MediaCache.isReadableLocalUri(context, message.parsedContent())
            }
            val durationSec = meta.voiceDurationMs?.takeIf { it > 0 }?.let { (it + 999) / 1000 }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMessage(message.id) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = stringResource(R.string.message_preview_voice),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.message_preview_voice),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            durationSec?.let { stringResource(R.string.media_center_voice_duration, it) },
                            formatDate(message.timestamp),
                            if (localAvailable) {
                                stringResource(R.string.media_center_cached)
                            } else {
                                stringResource(R.string.media_center_cache_missing)
                            }
                        ).joinToString(" · "),
                        color = LocalChatPalette.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { onOpenMessage(message.id) }) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.media_center_open_message),
                        tint = LocalChatPalette.current.textSecondary
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
private fun LocationList(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.message.id }, contentType = { "location" }) { item ->
            val message = item.message
            val payload = remember(message.content) { message.parsedLocation() }
            val label = payload?.label?.takeIf { it.isNotBlank() && it != "当前位置" }
                ?: stringResource(R.string.message_preview_location)
            val coord = payload?.let {
                stringResource(R.string.media_center_location_coords, it.latitude, it.longitude)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMessage(message.id) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = stringResource(R.string.message_preview_location),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(coord, formatDate(message.timestamp)).joinToString(" · "),
                        color = LocalChatPalette.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onOpenMessage(message.id) }) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.media_center_open_message),
                        tint = LocalChatPalette.current.textSecondary
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
private fun LinkList(items: List<MediaCenterItem>, onOpenMessage: (String) -> Unit, highlightQuery: String = "") {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { "${it.message.id}:${it.linkUrl}" }, contentType = { "link" }) { item ->
            val url = item.linkUrl.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().clickable { openWebLink(context, url) }.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Link, stringResource(R.string.media_center_links), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // 1.320：搜索时高亮匹配域名/链接
                    Text(
                        if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(url.toUri().host ?: url)
                        else highlightedText(url.toUri().host ?: url, highlightQuery),
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(url)
                        else highlightedText(url, highlightQuery),
                        color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(formatDate(item.message.timestamp), color = LocalChatPalette.current.textHint, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onOpenMessage(item.message.id) }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.media_center_open_message), tint = LocalChatPalette.current.textSecondary)
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.media_center_open_link), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 56.dp))
        }
    }
}

private fun mediaCenterItemMatches(item: MediaCenterItem, query: String): Boolean {
    val message = item.message
    val meta = message.parsedMeta()
    val fileName = meta.fileName.orEmpty()
    val mime = meta.fileMimeType.orEmpty()
    val link = item.linkUrl.orEmpty()
    val locationLabel = runCatching {
        val payload = message.parsedLocation()
        payload?.label.orEmpty()
    }.getOrDefault("")
    val content = when (item.category) {
        MediaCenterCategory.LINKS -> link
        MediaCenterCategory.LOCATION -> locationLabel.ifBlank { message.parsedContent() }
        MediaCenterCategory.FILES -> fileName.ifBlank { message.parsedContent() }
        else -> listOf(fileName, mime, message.parsedContent()).joinToString(" ")
    }
    return content.contains(query, ignoreCase = true) ||
        fileName.contains(query, ignoreCase = true) ||
        link.contains(query, ignoreCase = true) ||
        mime.contains(query, ignoreCase = true) ||
        locationLabel.contains(query, ignoreCase = true)
}

@Composable
private fun MediaCenterSearchEmpty() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.media_center_search_empty), color = LocalChatPalette.current.textSecondary)
        }
    }
}

@Composable
private fun MediaCenterEmpty(category: MediaCenterCategory) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = when (category) {
                    MediaCenterCategory.MEDIA -> Icons.Outlined.Image
                    MediaCenterCategory.FILES -> Icons.Outlined.Description
                    MediaCenterCategory.VOICE -> Icons.Outlined.Mic
                    MediaCenterCategory.LINKS -> Icons.Outlined.Link
                    MediaCenterCategory.LOCATION -> Icons.Outlined.LocationOn
                },
                contentDescription = null,
                tint = LocalChatPalette.current.textHint,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(stringResource(R.string.media_center_empty), color = LocalChatPalette.current.textHint)
        }
    }
}

internal fun MediaCenterCategory.labelResource(): Int = when (this) {
    MediaCenterCategory.MEDIA -> R.string.media_center_media
    MediaCenterCategory.FILES -> R.string.media_center_files
    MediaCenterCategory.VOICE -> R.string.media_center_voice
    MediaCenterCategory.LINKS -> R.string.media_center_links
    MediaCenterCategory.LOCATION -> R.string.media_center_location
}

private fun openWebLink(context: Context, url: String) {
    com.maodouchat.navigation.AppLinkOpener.openUserFacingUrl(context, url)
}

private fun openLocalContent(context: Context, rawUri: String, mimeType: String?) {
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val threadLocalDateFormatByLocale = ThreadLocal.withInitial { mutableMapOf<java.util.Locale, DateFormat>() }
private fun formatDate(timestamp: Long): String = threadLocalDateFormatByLocale.get().getOrPut(java.util.Locale.getDefault()) { DateFormat.getDateInstance(DateFormat.MEDIUM, java.util.Locale.getDefault()) }.format(Date(timestamp))

@Composable
private fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
