package com.maodouchat.ui.screen.chatlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.core.model.ChatListReceiptPolicy
import com.maodouchat.core.model.Receipt
import com.maodouchat.core.model.formatChatTime
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.domain.conversation.ChatListAvatarPolicy
import com.maodouchat.domain.conversation.ChatListDisplayNamePolicy
import com.maodouchat.domain.conversation.ChatListItemPreviewPolicy
import com.maodouchat.domain.messaging.forwardPreviewText
import com.maodouchat.core.model.Chat
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.MessageStatusIcon
import com.maodouchat.ui.screen.chatdetail.chatMessagePreviewTextResId
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.util.HapticGate
import com.maodouchat.util.RuntimeFlags

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
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    receipt: Receipt? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onBadgeClick: (() -> Unit)? = null
) {
    val displayName = ChatListDisplayNamePolicy.resolve(
        chat, isSecret,
        channelDefault = stringResource(R.string.chat_channel_default_name),
        groupDefault = stringResource(R.string.chat_group),
        unknownLabel = stringResource(R.string.chat_unknown)
    )
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
    val messagePreview = ChatListItemPreviewPolicy.resolveMessagePreview(
        isLocked = isLocked,
        lockedPreview = stringResource(R.string.chat_lock_list_preview),
        secretBlocked = secretListBlock,
        secretPreview = stringResource(R.string.secret_chat_notification_preview),
        lastMessageType = chat.lastMessageType,
        lastMessage = chat.lastMessage,
        nudgeLabel = stringResource(R.string.message_preview_nudge),
        mediaLabel = forwardPreviewText(chat.lastMessageType)?.let { stringResource(chatMessagePreviewTextResId(it)) },
        encryptedPlaceholder = stringResource(R.string.message_preview_encrypted)
    )
    // 1.103：对端正在输入 → 预览最优先（锁/密聊不泄露输入状态）
    val typingPreview = if (typingUserId != null && !isLocked && !secretListBlock) stringResource(R.string.chat_typing) else null
    // 1.146：待发送定时消息提示（最优先于草稿/消息预览）
    val scheduledLabel = if (scheduledCount > 0 && !isLocked)
        stringResource(R.string.chat_scheduled_list_preview, scheduledCount) else null
    val finalPreview = ChatListItemPreviewPolicy.resolveFinalPreview(
        typingPreview = typingPreview,
        draftPreview = if (!draftText.isNullOrBlank()) stringResource(R.string.chat_draft_prefix) + draftText else null,
        messagePreview = messagePreview,
        scheduledLabel = scheduledLabel
    )

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
            avatarUrl = ChatListAvatarPolicy.avatarUrl(chat, isSecret),
            // 9.268：TG 式会话列表头像 54dp（原 48dp）
            size = AvatarSize.CHAT_LIST,
            // 1.128：单聊显示对方在线绿点（群聊不显示）；1.141：密聊不显示（隐私）
            isOnline = ChatListAvatarPolicy.showOnlineDot(chat, isSecret)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 1.148：搜索时关键词高亮
                if (searchQuery.isNotBlank()) {
                    Text(
                        accentHighlightedText(displayName, searchQuery),
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
                    if (searchQuery.isNotBlank()) accentHighlightedText(finalPreview, searchQuery) else androidx.compose.ui.text.AnnotatedString(finalPreview),
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
