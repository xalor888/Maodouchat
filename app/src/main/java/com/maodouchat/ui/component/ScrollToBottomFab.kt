package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings

/**
 * Telegram-style floating "scroll to bottom" FAB.
 *
 * Shows when [visible] is true (user has scrolled up). Fades in with spring scale.
 * Displays [unreadCount] when > 0. Click triggers [onClick].
 */
@Composable
fun ScrollToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0
) {
    val motion = LocalMotionSettings.current

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (!motion.animationsEnabled) {
            fadeIn(snap())
        } else {
            fadeIn(spring(dampingRatio = 0.7f, stiffness = 400f)) +
                scaleIn(
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                    initialScale = 0.6f
                )
        },
        exit = if (!motion.animationsEnabled) {
            fadeOut(snap())
        } else {
            fadeOut(spring(dampingRatio = 0.8f, stiffness = 500f)) +
                scaleOut(
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                    targetScale = 0.6f
                )
        }
    ) {
        val elevation by animateFloatAsState(
            targetValue = if (visible) 6f else 0f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "fabElevation"
        )

        Box(
            modifier = modifier
                .size(44.dp)
                .shadow(elevation.dp, CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (unreadCount > 0) {
                val scale by animateFloatAsState(
                    targetValue = if (unreadCount > 0) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                    label = "unreadScale"
                )
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_latest),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun <T> snap() = androidx.compose.animation.core.snap<T>()
