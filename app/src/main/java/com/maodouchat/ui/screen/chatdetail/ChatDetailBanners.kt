package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 聊天详情页顶部的「横幅」一族（G109 从 `ChatDetailComponents.kt` 拆出，原 412 行）。
 *
 * 八个横幅，都是「常驻提示条」形态——出现在消息列表上方，提示当前会话的特殊状态：
 * 群加密警告、密聊（含封Sender 就绪/过期倒计时）、实时位置共享（含剩余时间倒计时）、
 * 阅后即焚、置顶消息、群公告、安全警告、未读摘要。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * - **实时位置与密聊封Sender 都有倒计时**，用 `mutableLongStateOf` + `LaunchedEffect` 驱动，
 *   不是每次重组都重算——否则会漂移；
 * - 置顶横幅只在有置顶消息时出现，且「能否管理」决定是否显示取消按钮；
 * - 群公告为空时不渲染（不是显示一个空条）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

@Composable
internal fun GroupEncryptionWarningBanner(warning: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(warning, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SecretChatBanner(
    onManage: () -> Unit = {},
    sealedSenderReady: Boolean = false,
    sealedSenderExpiresInSec: Long = 0L,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.secret_chat_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.secret_chat_banner_limit),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.secret_chat_safety_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    tint = if (sealedSenderReady) Primary else TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (sealedSenderReady) {
                        stringResource(
                            R.string.secret_chat_sealed_chip,
                            (sealedSenderExpiresInSec / 3600L).coerceAtLeast(0L)
                        )
                    } else {
                        stringResource(R.string.secret_chat_sealed_chip_pending)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sealedSenderReady) Primary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun LiveLocationSharingBanner(
    untilMs: Long?,
    onStop: () -> Unit
) {
    var remaining by remember(untilMs) {
        mutableLongStateOf(com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(untilMs))
    }
    LaunchedEffect(untilMs) {
        while (true) {
            remaining = com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(untilMs)
            if (remaining <= 0L) break
            kotlinx.coroutines.delay(1000L)
        }
    }
    val label = com.maodouchat.util.LiveLocationPolicy.formatRemaining(remaining)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.NearMe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.live_location_sharing_banner, label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.live_location_stop),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onStop)
        )
    }
}

@Composable
internal fun DisappearingMessagesBanner(
    seconds: Int,
    onChange: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onChange)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.disappear_banner_on, disappearSecondsLabel(seconds)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.disappear_banner_change),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun PinnedMessagesBanner(
    pins: List<com.maodouchat.network.PinnedMessageDto>,
    messages: List<Message>,
    canManage: Boolean,
    onOpen: (String) -> Unit,
    onUnpin: (String) -> Unit,
    // 1.49：显示置顶者（解析 userId → 显示名）
    resolvePinnerName: (String) -> String = { it },
    // 1.53：点击置顶者名称 → 打开其资料
    onPinnerClick: ((String) -> Unit)? = null
) {
    if (pins.isEmpty()) return
    var pinIndex by remember(pins.map { it.messageId }.joinToString()) { mutableIntStateOf(0) }
    val safeIndex = pinIndex.coerceIn(0, pins.lastIndex)
    val current = pins[safeIndex]
    val message = messages.firstOrNull { it.id == current.messageId }
    val preview = pinnedPreviewText(message)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable {
                onOpen(current.messageId)
                if (pins.size > 1) {
                    pinIndex = (safeIndex + 1) % pins.size
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (pins.size > 1) {
                    stringResource(R.string.chat_pinned_banner_title) +
                        " · ${safeIndex + 1}/${pins.size}"
                } else {
                    stringResource(R.string.chat_pinned_banner_title)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 1.49：置顶者（本地解析显示名，服务端不可见时回退 userId）；1.56：附带置顶时间；1.58：pinnedAt<=0 回退无时间格式
            if (current.pinnedBy.isNotBlank()) {
                Text(
                    text = if (current.pinnedAt > 0) {
                        stringResource(
                            R.string.chat_pinned_by_time,
                            resolvePinnerName(current.pinnedBy),
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                current.pinnedAt,
                                System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        )
                    } else {
                        stringResource(R.string.chat_pinned_by, resolvePinnerName(current.pinnedBy))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 1.53：点击置顶者名称打开其资料
                    modifier = if (onPinnerClick != null) {
                        Modifier.clickable { onPinnerClick(current.pinnedBy) }
                    } else {
                        Modifier
                    }
                )
            }
        }
        if (canManage) {
            TextButton(onClick = { onUnpin(current.messageId) }) {
                Text(stringResource(R.string.chat_message_unpin), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun GroupAnnouncementBanner(
    announcement: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.group_announcement_banner_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = announcement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = LocalChatPalette.current.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Identity / safety warning strip above the timeline.
 * CHANGED (sticky) uses stronger fill so it cannot read as a soft tip.
 */
@Composable
internal fun SecurityWarningBanner(
    warning: String,
    sticky: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(UnreadRed.copy(alpha = if (sticky) 0.16f else 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = LocalChatPalette.current.unreadRed, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            warning,
            color = LocalChatPalette.current.unreadRed,
            style = if (sticky) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun UnreadSummaryBanner(
    summary: String?,
    messageCount: Int,
    isLoading: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    // 1.194：复制未读摘要
    onCopy: () -> Unit = {}
) {
    val pulse by rememberMotionPulse(
        initialValue = 0.6f,
        targetValue = 1f,
        durationMillis = 1_200,
        label = "unreadSummaryPulse"
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Primary.copy(alpha = 0.10f),
                        Primary.copy(alpha = 0.04f)
                    )
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape)
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (messageCount > 0) pluralStringResource(R.plurals.chat_unread_summary_count, messageCount, messageCount) else stringResource(R.string.chat_unread_summary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = summary ?: stringResource(R.string.chat_generating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (summary != null) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.chat_view)) }
            // 1.194：复制未读摘要
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.chat_copy), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close_unread_summary), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
        }
    }
}
