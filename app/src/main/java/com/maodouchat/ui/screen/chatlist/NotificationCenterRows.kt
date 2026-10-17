package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.MaodouDimens

@Composable
internal fun DayHeader(dayTitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .padding(horizontal = MaodouDimens.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(dayTitle, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun NotificationRow(
    item: NotificationCenterItem,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    // 1.284：搜索关键词高亮
    highlightQuery: String = ""
) {
    val (icon, accent) = iconForType(item)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 8.48 修复 M4：未读行用淡主色背景区分（此前两分支相同 → 视觉高亮失效）
            .background(if (item.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)
            .then(
                if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = MaodouDimens.ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 1.284：搜索时标题高亮匹配关键词（无搜索时原样渲染）
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(item.title)
                    else highlightedText(item.title, highlightQuery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.count > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("${item.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    notifRelativeTime(item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(it)
                    else highlightedText(it, highlightQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.preview?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(it)
                    else highlightedText(it, highlightQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!item.read) {
            Box(modifier = Modifier.padding(top = 8.dp, start = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Spacer(modifier = Modifier.width(0.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun EmptyNotificationCenter(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(72.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.notif_center_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.notif_center_empty_subtitle), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
        }
    }
}
