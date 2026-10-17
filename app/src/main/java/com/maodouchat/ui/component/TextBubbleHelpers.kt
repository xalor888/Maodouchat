package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

// ─── resolveBubbleShape ───
@Composable
internal fun resolveBubbleShape(isOwnMessage: Boolean, isGroupEdge: Boolean): androidx.compose.ui.graphics.Shape {
    val base = if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent
    else com.maodouchat.ui.theme.LocalBubbleShapes.current.received
    val rounded = base as? androidx.compose.foundation.shape.RoundedCornerShape ?: return base
    val tight = androidx.compose.foundation.shape.CornerSize(4.5.dp)
    return if (isOwnMessage) {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = rounded.topStart,
            topEnd = if (!isGroupEdge) tight else rounded.topEnd,
            bottomStart = rounded.bottomStart,
            bottomEnd = if (isGroupEdge) tight else rounded.bottomEnd
        )
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (!isGroupEdge) tight else rounded.topStart,
            topEnd = rounded.topEnd,
            bottomStart = if (isGroupEdge) tight else rounded.bottomStart,
            bottomEnd = rounded.bottomEnd
        )
    }
}

/** 输入框上方的「回复某条消息」提示条 */

// ─── ReplyTargetBar ───
@Composable
fun ReplyTargetBar(senderName: String, preview: String, onCancel: () -> Unit) {
    val palette = LocalChatPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chatInputBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.message_reply_to, senderName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(preview, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textHint, maxLines = 1)
        }
        androidx.compose.material3.TextButton(onClick = onCancel) {
            Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textHint)
        }
    }
}

@Preview(showBackground = true)

// ─── RoleBadge ───
@Composable
internal fun RoleBadge(label: String, owner: Boolean) {
    val background = if (owner) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (owner) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = foreground,
        modifier = Modifier
            .padding(start = 4.dp, bottom = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
