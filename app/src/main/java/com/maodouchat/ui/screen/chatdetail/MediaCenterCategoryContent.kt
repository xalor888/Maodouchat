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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.maodouchat.ui.component.ZoomableAsyncImage
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import java.io.File

/** 媒体中心的分类内容区：按分类（图片/语音/位置/链接/文件）展示条目，
含搜索、预览、导出；网格与图片预览已拆到同包 MediaCenterContentGrid / MediaCenterImageViewer。 */
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
