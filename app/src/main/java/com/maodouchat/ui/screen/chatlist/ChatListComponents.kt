package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.maodouchat.R
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.MessageStatusIcon
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.AnimatedBottomNav
import com.maodouchat.ui.component.BottomNavItem
import com.maodouchat.ui.component.LiquidBottomTabItem
import com.maodouchat.ui.component.LiquidBottomTabs
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.util.HapticGate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatListItem(
    chat: Chat,
    modifier: Modifier = Modifier,
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

// SimpleDateFormat 非线程安全，ThreadLocal 每线程复用一个；只调 format，不残留状态。
private val chatTimeFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
private val chatWeekdayFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("EEE", Locale.getDefault()) }
private val chatDateFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("MM/dd", Locale.getDefault()) }

internal fun formatChatTime(ts: Long): String {
    if (ts <= 0L) return ""
    val cal = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = ts }
    return if (cal.get(Calendar.YEAR) == msg.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)) {
        chatTimeFormat.get().format(Date(ts))
    } else {
        // 9.278：TG 式时间分段——近一周内显示星期缩写（如「周一」/Mon），更早才显示日期
        val dayDiff = daysBetween(msg, cal)
        if (dayDiff in 1..6) {
            chatWeekdayFormat.get().format(Date(ts))
        } else {
            chatDateFormat.get().format(Date(ts))
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
