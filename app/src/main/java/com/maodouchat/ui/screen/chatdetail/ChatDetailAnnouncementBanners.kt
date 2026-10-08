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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.graphicsLayer
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.Primary
import androidx.compose.runtime.getValue

// 群公告 / 未读摘要横幅（从 ChatDetailBanners.kt 拆出，纯搬移）。

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
