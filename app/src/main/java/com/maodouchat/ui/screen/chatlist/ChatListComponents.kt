package com.maodouchat.ui.screen.chatlist


import android.annotation.SuppressLint
import com.maodouchat.util.RuntimeFlags
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightSurface
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.MissedCall
import com.maodouchat.ui.component.MessageStatusIcon
import com.maodouchat.network.ApiService
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.AnimatedBottomNav
import com.maodouchat.ui.component.BottomNavItem
import com.maodouchat.ui.component.FloatingBottomBarContentPadding
import com.maodouchat.ui.component.LiquidBottomTabItem
import com.maodouchat.ui.component.LiquidBottomTabs
import com.maodouchat.ui.component.PullToRefreshLayout
import com.maodouchat.ui.component.SearchBar
import com.maodouchat.ui.component.SwipeableChatItem
import com.maodouchat.ui.component.ShimmerChatRow
import com.maodouchat.navigation.MainTab
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.util.ChatFolderPolicy
import com.maodouchat.util.HapticGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled


@Composable
internal fun ChatFolderStrip(
    folders: List<com.maodouchat.util.ChatFolder>,
    selectedFolderId: String?,
    secretChatCount: Int,
    lockedChatCount: Int,
    unreadInFolder: (String) -> Int,
    onSelectFolder: (String?) -> Unit,
    onManage: () -> Unit,
    onCreate: () -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderChip(stringResource(R.string.chat_folder_all), selectedFolderId.isNullOrBlank(), 0) { onSelectFolder(null) }
        FolderChip(stringResource(R.string.chat_folder_unread), selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_UNREAD_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_UNREAD_ID) }
        FolderChip(stringResource(R.string.chat_folder_groups), selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_GROUPS_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_GROUPS_ID) }
        FolderChip(stringResource(R.string.chat_folder_direct), selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_DIRECT_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_DIRECT_ID) }
        if (secretChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID) {
            FolderChip(stringResource(R.string.chat_folder_secret), selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID, secretChatCount) { onSelectFolder(ChatFolderPolicy.SYSTEM_SECRET_ID) }
        }
        if (lockedChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID) {
            FolderChip(stringResource(R.string.chat_folder_locked), selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID, lockedChatCount) { onSelectFolder(ChatFolderPolicy.SYSTEM_LOCKED_ID) }
        }
        folders.forEach { folder ->
            FolderChip(folder.name, selectedFolderId == folder.id, unreadInFolder(folder.id)) { onSelectFolder(folder.id) }
        }
        TextButton(
            onClick = onCreate,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text(stringResource(R.string.chat_folder_create), style = MaterialTheme.typography.labelMedium) }
        TextButton(
            onClick = onManage,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text(stringResource(R.string.chat_folder_manage), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
internal fun FolderChip(label: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val chipLabel = if (badge > 0) "$label ${if (badge > 99) "99+" else badge}" else label
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(chipLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.padding(end = 6.dp).height(32.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.outline,
        )
    )
}

@Composable
internal fun ShimmerChatList() {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(8) { ShimmerChatRow() }
    }
}

@Composable
internal fun EmptyChatState(
    hasSearchQuery: Boolean,
    showArchived: Boolean,
    selectedFolderId: String?,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
    onAddContact: () -> Unit,
    onScan: (() -> Unit)?
) {
    // 8.52 UX：初次加载失败且无本地缓存时，用错误空态代替误导性的「还没有聊天」+ 重试入口
    if (loadError != null && !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()) {
        EmptyState(
            type = EmptyStateType.NETWORK_ERROR,
            title = stringResource(R.string.chat_load_failed_title),
            subtitle = loadError,
            actionText = stringResource(R.string.chat_load_failed_retry),
            onAction = onRetry
        )
        return
    }
    val title = when {
        hasSearchQuery -> stringResource(R.string.chat_empty_search_title)
        showArchived -> stringResource(R.string.chat_empty_archived_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> stringResource(R.string.chat_folder_empty_unread_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> stringResource(R.string.chat_folder_empty_groups_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> stringResource(R.string.chat_folder_empty_direct_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> stringResource(R.string.chat_folder_empty_secret_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> stringResource(R.string.chat_folder_empty_locked_title)
        !selectedFolderId.isNullOrBlank() -> stringResource(R.string.chat_folder_empty)
        else -> stringResource(R.string.chat_empty_title)
    }
    val subtitle = when {
        hasSearchQuery -> stringResource(R.string.chat_empty_search_subtitle)
        showArchived -> stringResource(R.string.chat_empty_archived_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> stringResource(R.string.chat_folder_empty_unread_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> stringResource(R.string.chat_folder_empty_groups_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> stringResource(R.string.chat_folder_empty_direct_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> stringResource(R.string.chat_folder_empty_secret_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> stringResource(R.string.chat_folder_empty_locked_subtitle)
        // 8.45：此前误用「查看全部会话」操作按钮文案作空态副标题
        !selectedFolderId.isNullOrBlank() -> stringResource(R.string.chat_folder_empty_subtitle)
        else -> stringResource(R.string.chat_empty_subtitle)
    }
    val showActions = !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()
    EmptyState(
        type = if (hasSearchQuery) EmptyStateType.SEARCH else EmptyStateType.CHAT_LIST,
        title = title,
        subtitle = subtitle,
        actionText = if (showActions) stringResource(R.string.chat_empty_action_add) else null,
        onAction = if (showActions) onAddContact else null,
        secondaryActionText = if (showActions && onScan != null) stringResource(R.string.chat_empty_action_scan) else null,
        onSecondaryAction = if (showActions) onScan else null
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatListItem(
    chat: Chat,
    draft: ChatDraftEntity? = null,
    typingUserId: String? = null,
    scheduledCount: Int = 0,
    searchQuery: String = "",
    isLocked: Boolean = false,
    isSecret: Boolean = false,
    identityChanged: Boolean = false,
    isDeleting: Boolean = false,
    // 1.368：多选模式勾选态（勾选时显示选中复选框 + 高亮背景）
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    receipt: ChatListReceiptPolicy.Receipt? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onBadgeClick: (() -> Unit)? = null
) {
    val otherUser = chat.participants.firstOrNull()
    val rawDisplayName = when {
        chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_channel_default_name)
        chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_group)
        else -> otherUser?.displayName?.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_unknown)
    }
    val displayName = if (isSecret || chat.isSecret) {
        com.maodouchat.security.SecretChatPolicy.mosaicDisplayName(rawDisplayName)
    } else rawDisplayName
    val haptic = LocalHapticFeedback.current
    val hapticContext = LocalContext.current
    val motion = LocalMotionSettings.current
    val interactionSource = remember(chat.id) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.82f, stiffness = 520f),
        label = "chatListPressScale"
    )
    val hasUnread = ChatListReceiptPolicy.showUnreadBadge(chat.unreadCount, chat.markedUnread)
    val unreadLabel = ChatListReceiptPolicy.unreadBadgeText(chat.unreadCount, chat.markedUnread)
    val listCtx = LocalContext.current
    val secretListBlock = isSecret && RuntimeFlags.isEnabled(listCtx, RuntimeFlags.SECRET_LIST_PREVIEW_BLOCK)
    val draftText = if (isLocked || secretListBlock) null else draft?.text?.takeIf(String::isNotBlank)
    val lockedPreview = stringResource(R.string.chat_lock_list_preview)
    val secretPreview = stringResource(R.string.secret_chat_notification_preview)
    val messagePreview = if (isLocked) lockedPreview
    else if (secretListBlock) secretPreview
    else when (chat.lastMessageType) {
        MessageType.IMAGE -> stringResource(R.string.message_preview_image)
        MessageType.GIF -> stringResource(R.string.message_preview_gif)
        MessageType.STICKER -> stringResource(R.string.message_preview_sticker)
        MessageType.LOCATION -> stringResource(R.string.message_preview_location)
        MessageType.VOICE -> stringResource(R.string.message_preview_voice)
        MessageType.VIDEO -> stringResource(R.string.message_preview_video)
        MessageType.FILE -> stringResource(R.string.message_preview_file)
        MessageType.NUDGE -> stringResource(R.string.message_preview_nudge)
        else -> {
            val placeholder = stringResource(R.string.message_preview_encrypted)
            val stripped = com.maodouchat.data.repository.ChatListPreviewPolicy.listVisibleText(
                chat.lastMessage,
                placeholder
            )
            if (stripped == placeholder) placeholder
            else com.maodouchat.messaging.ChatMarkdown.stripContactCardMarker(stripped)
        }
    }
    // 1.103：对端正在输入 → 预览最优先（锁/密聊不泄露输入状态）
    val typingPreview = if (typingUserId != null && !isLocked && !secretListBlock) stringResource(R.string.chat_typing) else null
    val preview = typingPreview
        ?: if (!draftText.isNullOrBlank()) stringResource(R.string.chat_draft_prefix) + draftText
        else messagePreview
    // 1.146：待发送定时消息提示（最优先于草稿/消息预览）
    val scheduledLabel = if (scheduledCount > 0 && !isLocked) {
        stringResource(R.string.chat_scheduled_list_preview, scheduledCount)
    } else null
    val finalPreview = scheduledLabel ?: preview

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .alpha(if (isDeleting) 0.45f else 1f)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    chat.pinnedAt > 0 -> MaterialTheme.colorScheme.surfaceContainer
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = {
                    if (!isDeleting) onClick()
                },
                onLongClick = {
                    HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                    if (!isDeleting) onLongClick()
                }
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelecting) {
            // 1.368：多选模式前置复选框
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Avatar(
            name = displayName,
            avatarUrl = if (isSecret || chat.isSecret) null else if (chat.isGroup) chat.groupAvatar else otherUser?.avatar,
            // 9.268：TG 式会话列表头像 54dp（原 48dp）
            size = AvatarSize.CHAT_LIST,
            // 1.128：单聊显示对方在线绿点（群聊不显示）；1.141：密聊不显示（隐私）
            isOnline = !chat.isGroup && otherUser?.isOnline == true && !isSecret
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 1.148：搜索时关键词高亮
                if (searchQuery.isNotBlank()) {
                    Text(
                        highlightedText(displayName, searchQuery),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isSecret || chat.isSecret) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.secret_chat_list_indicator),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Text(
                    formatChatTime(chat.lastMessageTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (identityChanged) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = stringResource(R.string.chat_identity_changed_warning_short),
                        modifier = Modifier.size(14.dp),
                        tint = LocalChatPalette.current.unreadRed
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (!draftText.isNullOrBlank() && scheduledLabel == null && typingPreview == null) {
                    Icon(Icons.Outlined.EditNote, contentDescription = null, tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                } else if (receipt?.fromMe == true && scheduledLabel == null && typingPreview == null) {
                    MessageStatusIcon(status = receipt.status, tint = LocalChatPalette.current.textHint)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (searchQuery.isNotBlank()) highlightedText(finalPreview, searchQuery) else androidx.compose.ui.text.AnnotatedString(finalPreview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (typingPreview != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (chat.notificationsMuted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = LocalChatPalette.current.textHint
                    )
                }
                if (hasUnread) {
                    Spacer(Modifier.width(8.dp))
                    // 1.182：点击未读角标直接标记已读（不进入会话）
                    // 9.269：TG 式未读角标——全胶囊形 + 绿色（TG 标志观感，原主题色圆角矩形）
                    androidx.compose.material3.Badge(
                        modifier = if (onBadgeClick != null) Modifier.clickable(onClick = onBadgeClick) else Modifier,
                        containerColor = if (chat.notificationsMuted) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (chat.notificationsMuted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    ) {
                        if (unreadLabel.isNotBlank()) {
                            Text(unreadLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MissedCallsCard(calls: List<MissedCall>, onOpen: () -> Unit) {
    val unread = calls.count { !it.isRead }
    // 9.215 修复：missed_calls 原调用未传数量参数，%1$d 会原样显示；转 plurals 并传入计数
    val title = if (unread > 0) pluralStringResource(R.plurals.missed_calls_with_unread, calls.size, calls.size, unread) else pluralStringResource(R.plurals.missed_calls, calls.size, calls.size)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
            .combinedClickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(calls.firstOrNull()?.callerName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onOpen) { Text(stringResource(R.string.schedule_view_all)) }
    }
}

internal data class CallLogRow(
    val id: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val direction: com.maodouchat.call.CallLogStore.Direction,
    val state: com.maodouchat.call.CallLogStore.State,
    val at: Long,
    val durationMs: Long
)

private fun formatCallDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = (durationMs / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MissedCallsSheet(
    rows: List<CallLogRow>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onOpenChat: (userId: String, name: String, video: Boolean) -> Unit,
    // 1.289：长按单条删除（与通话记录页单条删除一致）
    onDeleteRow: (CallLogRow) -> Unit = {}
) {
    val motion = LocalMotionSettings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_log_title)) },
        text = {
            if (rows.isEmpty()) Text(stringResource(R.string.missed_calls_empty))
            else LazyColumn {
                items(rows, key = { it.id }) { call ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = motion.listItemFadeInSpec(),
                                fadeOutSpec = motion.listItemFadeOutSpec(),
                                placementSpec = motion.listItemPlacementSpec()
                            )
                            .combinedClickable(
                                onClick = { onOpenChat(call.peerId, call.peerName, call.video) },
                                // 1.289：长按删除该条通话记录
                                onLongClick = { onDeleteRow(call) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            // CallMade/CallReceived 不在 material-icons-core；用 Call + tint 区分状态
                            imageVector = Icons.Filled.Call,
                            contentDescription = null,
                            tint = if (call.state == com.maodouchat.call.CallLogStore.State.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(call.peerName, fontWeight = FontWeight.Medium)
                            Text(
                                (if (call.video) stringResource(R.string.call_video) else stringResource(R.string.call_audio)) +
                                    " · " + relativeTime(call.at) +
                                    when (call.state) {
                                        com.maodouchat.call.CallLogStore.State.MISSED -> " · " + stringResource(R.string.missed_calls_badge)
                                        com.maodouchat.call.CallLogStore.State.ANSWERED -> {
                                            val d = formatCallDuration(call.durationMs)
                                            if (d.isNotEmpty()) " · " + d else ""
                                        }
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear) { Text(stringResource(R.string.chat_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
fun BottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val unreadTotal by UnreadBadgeStore.totalUnread.collectAsState()
    val exploreBadge by ExploreBadgeStore.count.collectAsState()
    val tabs = listOf(
        LiquidBottomTabItem(
            icon = Icons.Outlined.ChatBubbleOutline,
            selectedIcon = Icons.Filled.ChatBubble,
            label = stringResource(R.string.nav_chats),
            badgeCount = unreadTotal
        ),
        LiquidBottomTabItem(
            icon = Icons.Outlined.Group,
            selectedIcon = Icons.Filled.Group,
            label = stringResource(R.string.nav_contacts)
        ),
        LiquidBottomTabItem(
            icon = Icons.Outlined.Explore,
            selectedIcon = Icons.Filled.Explore,
            label = stringResource(R.string.nav_explore),
            badgeCount = exploreBadge
        ),
        LiquidBottomTabItem(
            icon = Icons.Outlined.Settings,
            selectedIcon = Icons.Filled.Settings,
            label = stringResource(R.string.nav_settings)
        )
    )
    val liquidGlass = LocalLiquidGlassEnabled.current
    val floatingDockOn by com.maodouchat.util.ChromePreferences.floatingDock.collectAsState()
    if (liquidGlass && floatingDockOn) {
        LiquidBottomTabs(
            selectedTabIndex = selectedTab,
            onTabSelected = onTabSelected,
            tabs = tabs,
            modifier = modifier
        )
    } else {
        AnimatedBottomNav(
            selectedIndex = selectedTab,
            onItemSelected = onTabSelected,
            items = tabs.map { tab ->
                BottomNavItem(
                    icon = tab.icon,
                    selectedIcon = tab.selectedIcon,
                    label = tab.label,
                    badgeCount = tab.badgeCount
                )
            },
            modifier = modifier.navigationBarsPadding()
        )
    }
}

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    // 8.45：此前硬编码 "now"/"5m"/"3h" 英文——改用地区分区间的相对时间（中英文界面均正确）
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        ts,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

internal fun formatChatTime(ts: Long): String {
    if (ts <= 0L) return ""
    val cal = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = ts }
    return if (cal.get(Calendar.YEAR) == msg.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        // 9.278：TG 式时间分段——近一周内显示星期缩写（如「周一」/Mon），更早才显示日期
        val dayDiff = daysBetween(msg, cal)
        if (dayDiff in 1..6) {
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
        } else {
            SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(ts))
        }
    }
}

/** 两个日历之间的自然日差（按零点对齐）。 */
internal fun daysBetween(earlier: Calendar, later: Calendar): Int {
    val e = Calendar.getInstance().apply { timeInMillis = earlier.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val l = Calendar.getInstance().apply { timeInMillis = later.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return ((l.timeInMillis - e.timeInMillis) / 86400000L).toInt()
}

/**
 * 8.47：智能归档建议卡片（纯本地启发式，无 AI/服务端调用）。
 * 展示前 [suggestions] 条；每条可「归档」或「忽略」，整卡可一键关闭。
 */
@Composable
internal fun ArchiveSuggestionsCard(
    suggestions: List<com.maodouchat.ai.AiArchiveSuggestion.Suggestion>,
    chatsById: Map<String, Chat>,
    onArchive: (String) -> Unit,
    onDismissOne: (String) -> Unit,
    onDismissAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ai_enhance_archive_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismissAll, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            stringResource(R.string.ai_enhance_archive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        suggestions.forEach { suggestion ->
            val chat = chatsById[suggestion.chatId]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = chatNameForSuggestion(chat) ?: suggestion.chatId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = suggestion.reason.take(40),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onArchive(suggestion.chatId) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.chat_archive), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onDismissOne(suggestion.chatId) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.common_later), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun chatNameForSuggestion(chat: Chat?): String? {
    if (chat == null) return null
    return chat.groupName
        ?: chat.participants.firstOrNull()?.displayName
        ?: chat.participants.firstOrNull()?.name
}

// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
private fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}