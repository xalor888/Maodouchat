package com.maodouchat.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.UnreadRed

/**
 * An animated unread badge with spring-scale entry.
 *
 * The badge pops in with a bouncy spring when `count` transitions from 0 to >0,
 * and shrinks out when it returns to 0. Supports a "dot" mode for
 * `markedUnread` (count = 0 but show a dot).
 *
 * @param count Unread message count (0 = hidden, unless showDot is true)
 * @param showDot When true and count == 0, shows a small dot badge
 * @param modifier Modifier for the composable
 * @param color Badge background color
 */
@Composable
fun AnimatedBadge(
    count: Int,
    modifier: Modifier = Modifier,
    showDot: Boolean = false,
    /** Muted chats use a grey badge (WeChat/Telegram style) instead of red. */
    muted: Boolean = false,
    color: Color = UnreadRed
) {
    val badgeColor = if (muted) Color(0xFF8E8E93) else color
    val visible = count > 0 || showDot
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.45f,
            stiffness = 600f
        ),
        label = "badgeScale"
    )

    if (scale > 0.01f) {
        if (showDot && count == 0) {
            // Small dot for marked-unread
            Box(
                modifier = modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = scale.coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor)
            )
        } else {
            val label = when {
                count > 99 -> "99+"
                count > 0 -> count.toString()
                else -> ""
            }
            val minWidth = if (label.length <= 1) 18.dp else 22.dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = scale.coerceIn(0f, 1f)
                    }
                    .widthIn(min = minWidth)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(badgeColor)
                    .padding(horizontal = if (label.length > 1) 5.dp else 0.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }
}
