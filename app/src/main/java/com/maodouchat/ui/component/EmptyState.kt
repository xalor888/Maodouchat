package com.maodouchat.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.AvatarGradients
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.Primary

enum class EmptyStateType {
    CHAT_LIST, SEARCH, CONTACTS, MOMENTS, NETWORK_ERROR, GENERIC
}

/**
 * A polished empty/error state component with animated icon entry.
 * Provides consistent empty states across the app.
 */
@Composable
fun EmptyState(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    type: EmptyStateType = EmptyStateType.GENERIC
) {
    val motion = LocalMotionSettings.current
    var animPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animPlayed = true }

    val resolvedIcon = icon ?: when (type) {
        EmptyStateType.CHAT_LIST -> Icons.Outlined.ChatBubbleOutline
        EmptyStateType.SEARCH -> Icons.Outlined.SearchOff
        EmptyStateType.CONTACTS -> Icons.Outlined.Inbox
        EmptyStateType.MOMENTS -> Icons.Outlined.Inbox
        EmptyStateType.NETWORK_ERROR -> Icons.Outlined.WifiOff
        EmptyStateType.GENERIC -> Icons.Outlined.ErrorOutline
    }

    val iconScale by animateFloatAsState(
        targetValue = if (animPlayed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "emptyStateIconScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (animPlayed) 1f else 0f,
        animationSpec = tween(motion.duration(MotionTokens.Emphasized), delayMillis = 100),
        label = "emptyStateContentAlpha"
    )

    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated gradient circle with icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = iconScale.coerceIn(0f, 1f)
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            AvatarGradients[0].map { it.copy(alpha = 0.12f) }
                        )
                    )
            ) {
                Icon(
                    imageVector = resolvedIcon,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = contentAlpha }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                if (actionText != null && onAction != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (secondaryActionText != null && onSecondaryAction != null) {
                        Row(horizontalArrangement = Arrangement.Center) {
                            Button(onClick = onAction) {
                                Text(actionText)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedButton(onClick = onSecondaryAction) {
                                Text(secondaryActionText, color = Primary)
                            }
                        }
                    } else {
                        TextButton(onClick = onAction) {
                            Text(actionText, color = Primary)
                        }
                    }
                }
            }
        }
    }
}
