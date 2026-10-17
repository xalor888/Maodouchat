package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint

@Composable
internal fun ReactionSummaryRow(
    message: Message,
    currentUserId: String?,
    isOwnMessage: Boolean,
    onReactionClick: ((String) -> Unit)? = null
) {
    val palette = LocalChatPalette.current
    if (message.reactions.isEmpty()) return
    val grouped = remember(message.reactions) {
        message.reactions.groupBy { it.emoji }
            .map { (emoji, reactions) -> emoji to reactions.sortedBy { it.reactedAt } }
            .sortedBy { (_, reactions) -> reactions.firstOrNull()?.reactedAt ?: 0L }
    }
    val visible = grouped.take(24)
    val overflow = (grouped.size - visible.size).coerceAtLeast(0)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        visible.forEach { (emoji, reactions) ->
            val reactedByMe = currentUserId != null && reactions.any { it.userId == currentUserId }
            Text(
                text = "$emoji ${reactions.size}",
                style = MaterialTheme.typography.labelMedium,
                color = if (reactedByMe) Primary else if (isOwnMessage) LocalSentBubbleContent.current else OnSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            reactedByMe -> Primary.copy(alpha = 0.14f)
                            isOwnMessage -> Color.White.copy(alpha = 0.18f)
                            else -> palette.chatInputBackground
                        }
                    )
                    .then(
                        if (onReactionClick != null) {
                            Modifier.clickable { onReactionClick(emoji) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOwnMessage) LocalSentBubbleContent.current else TextHint,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isOwnMessage) Color.White.copy(alpha = 0.18f)
                        else palette.chatInputBackground
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}
