package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// 置顶消息横幅（从 ChatDetailBanners.kt 拆出，纯搬移）。

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
