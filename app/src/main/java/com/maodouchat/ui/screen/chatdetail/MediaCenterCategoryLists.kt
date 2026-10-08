package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.component.SearchHighlightAccent
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.MediaCache
import java.text.DateFormat
import java.util.Date
// MediaCenter 的文件/语音/位置/链接列表簇：从 MediaCenterCategoryContent 拆出的同包行组件。
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileList(
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
internal fun VoiceList(
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
internal fun LocationList(
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
internal fun LinkList(items: List<MediaCenterItem>, onOpenMessage: (String) -> Unit, highlightQuery: String = "") {
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

private fun openWebLink(context: Context, url: String) {
    com.maodouchat.navigation.AppLinkOpener.openUserFacingUrl(context, url)
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
