package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.ShimmerBox
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
fun StarredMessagesScreen(
    onBack: () -> Unit,
    onOpenMessage: (chatId: String, messageId: String) -> Unit = { _, _ -> },
    viewModel: StarredMessagesViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val motion = LocalMotionSettings.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 1.161：清空全部确认
    var showClearStarredConfirm by rememberSaveable { mutableStateOf(false) }
    val title = if (state.globalScope) {
        stringResource(R.string.settings_starred_messages)
    } else {
        stringResource(R.string.chat_starred_messages)
    }
    val meLabel = stringResource(R.string.chat_sender_me)
    val groupLabel = stringResource(R.string.chat_group)
    val memberLabel = stringResource(R.string.chat_group_member)
    val filteredMessages = remember(state.messages, state.chatsById, state.chat, state.currentUserId, searchQuery, meLabel, groupLabel, memberLabel) {
        val q = searchQuery.trim()
        if (q.isEmpty()) state.messages
        else state.messages.filter { message ->
            val scopeChat = state.chatsById[message.chatId] ?: state.chat
            val sender = when {
                message.senderId == state.currentUserId -> meLabel
                else -> scopeChat?.participants?.firstOrNull { it.id == message.senderId }?.displayName ?: memberLabel
            }
            val chatName = when {
                scopeChat == null -> groupLabel
                scopeChat.isGroup -> scopeChat.groupName?.takeIf { it.isNotBlank() } ?: groupLabel
                else -> {
                    val other = scopeChat.participants.firstOrNull { it.id != state.currentUserId }
                        ?: scopeChat.participants.firstOrNull()
                    other?.displayName ?: memberLabel
                }
            }
            // 9.153：与正文解析口径一致——meta 恒在末尾，取最后一个 <meta> 之前的内容做搜索预览
            val preview = message.content.substringBeforeLast("<meta>").trim()
            sender.contains(q, ignoreCase = true) ||
                chatName.contains(q, ignoreCase = true) ||
                preview.contains(q, ignoreCase = true)
        }
    }
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = state.secretChatId,
        deviceHint = com.maodouchat.watermark.DeviceHint.androidId(context)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    if (state.isChatLocked && !state.isChatUnlocked) {
        ChatLockGate(
            chatName = state.chat?.groupName?.takeIf { it.isNotBlank() }
                ?: state.chat?.participants?.firstOrNull { it.id != state.currentUserId }?.displayName
                ?: stringResource(R.string.chat_this_chat),
            onUnlock = { pin, onResult -> viewModel.unlockChatWithPin(pin, onResult) },
            onForgotPin = onBack
        )
    } else {
    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    // 1.161：清空全部收藏
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = { showClearStarredConfirm = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.starred_clear_all), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.shadow(1.dp)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(6) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        ShimmerBox(modifier = Modifier.size(36.dp), cornerRadius = 18.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShimmerBox(modifier = Modifier.width(120.dp).height(14.dp))
                            ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp))
                        }
                    }
                }
            }
            state.error != null -> EmptyState(
                title = stringResource(R.string.starred_load_failed),
                subtitle = state.error,
                type = EmptyStateType.NETWORK_ERROR,
                actionText = stringResource(R.string.chat_refresh),
                onAction = { viewModel.load() },
                modifier = Modifier.padding(padding)
            )
            state.messages.isEmpty() -> EmptyState(
                title = stringResource(R.string.starred_empty),
                type = EmptyStateType.GENERIC,
                modifier = Modifier.padding(padding)
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(160) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.starred_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (filteredMessages.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.starred_search_empty),
                        type = EmptyStateType.GENERIC,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredMessages, key = { it.id }, contentType = { "starred_${it.type.name}" }) { message ->
                            val scopeChat = state.chatsById[message.chatId] ?: state.chat
                            val starredCopyPreview = message.starredPreview(context)
                            StarredMessageRow(
                                message = message,
                                senderName = senderName(scopeChat, message, state.currentUserId),
                                chatTitle = if (state.globalScope) chatTitle(scopeChat, state.currentUserId) else null,
                                // 1.232：搜索高亮
                                searchQuery = searchQuery,
                                // 1.243：长按复制内容
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.settings_starred_messages), starredCopyPreview))
                                    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                                },
                                onClick = {
                                    if (message.chatId.isNotBlank()) {
                                        onOpenMessage(message.chatId, message.id)
                                    }
                                },
                                onUnstar = { viewModel.unstarMessage(message.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = motion.listItemFadeInSpec(),
                                    fadeOutSpec = motion.listItemFadeOutSpec(),
                                    placementSpec = motion.listItemPlacementSpec()
                                )
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }
        }
    }
    } // end else(Scaffold)
    } // secret watermark Box

    // 1.161：清空全部收藏确认
    if (showClearStarredConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStarredConfirm = false },
            title = { Text(stringResource(R.string.starred_clear_all)) },
            text = { Text(stringResource(R.string.starred_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearStarredConfirm = false
                    viewModel.clearAllStarred()
                }) { Text(stringResource(R.string.common_clear), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearStarredConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

private fun StarredMessageRow(
    message: Message,
    senderName: String,
    chatTitle: String?,
    modifier: Modifier = Modifier,
    // 1.232：搜索高亮
    searchQuery: String = "",
    // 1.243：长按复制内容
    onCopy: (() -> Unit)? = null,
    onClick: () -> Unit,
    onUnstar: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onCopy != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onCopy)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Avatar(name = senderName, size = AvatarSize.SM)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(senderName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onUnstar,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.starred_unstar), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            if (!chatTitle.isNullOrBlank()) {
                Text(chatTitle, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 1.232：搜索时高亮匹配关键词
            val previewText = message.starredPreview(context)
            Text(
                if (searchQuery.isNotBlank()) highlightedText(previewText, searchQuery) else androidx.compose.ui.text.AnnotatedString(previewText),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(formatStarredTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun senderName(chat: Chat?, message: Message, currentUserId: String): String {
    if (message.senderId == currentUserId) return stringResource(R.string.chat_sender_me)
    return chat?.participants?.firstOrNull { it.id == message.senderId }?.displayName
        ?: stringResource(R.string.chat_group_member)
}

@Composable
private fun chatTitle(chat: Chat?, currentUserId: String): String {
    if (chat == null) return stringResource(R.string.chat_group)
    if (chat.isGroup) {
        return chat.groupName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_group)
    }
    val other = chat.participants.firstOrNull { it.id != currentUserId } ?: chat.participants.firstOrNull()
    return other?.displayName ?: stringResource(R.string.chat_group_member)
}

@Composable
private fun Message.starredPreview(context: android.content.Context): String = when (type) {
    MessageType.TEXT, MessageType.MARKDOWN ->
        com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
            parsedContent(),
            context.getString(R.string.starred_encrypted_message),
        )
    MessageType.IMAGE -> stringResource(R.string.message_preview_image)
    MessageType.GIF -> stringResource(R.string.message_preview_gif)
    MessageType.STICKER -> stringResource(R.string.message_preview_sticker)
    MessageType.LOCATION -> stringResource(R.string.message_preview_location)
    MessageType.VIDEO -> stringResource(R.string.message_preview_video)
    MessageType.VOICE -> stringResource(R.string.message_preview_voice)
    MessageType.FILE -> stringResource(R.string.message_preview_file)
    MessageType.REVOKED -> stringResource(R.string.chat_message_revoked_placeholder)
    else -> content
}

// DateFormat 非线程安全：ThreadLocal 按 locale 分键缓存，语言切换后自动重建。
private val starredTimeFormatCache = ThreadLocal.withInitial { mutableMapOf<Locale, DateFormat>() }

private fun formatStarredTime(timestamp: Long): String {
    val locale = Locale.getDefault()
    return starredTimeFormatCache.get().getOrPut(locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    }.format(Date(timestamp))
}

@Composable
private fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
