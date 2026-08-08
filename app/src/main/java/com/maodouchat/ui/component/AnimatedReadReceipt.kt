package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.OnlineGreen

/**
 * Animated read receipt: single check → double check → blue double check.
 *
 * Each state transition uses scale+fade animated content with spring physics.
 *
 * @param state 0 = sent (single check), 1 = delivered (double check), 2 = read (blue double check)
 */
@Composable
fun AnimatedReadReceipt(
    state: Int,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotionSettings.current

    AnimatedContent(
        targetState = state.coerceIn(0, 2),
        modifier = modifier,
        transitionSpec = {
            if (!motion.animationsEnabled) {
                fadeIn() togetherWith fadeOut()
            } else {
                (slideInVertically(
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)
                ) { it / 3 } + fadeIn(
                    spring(dampingRatio = 0.7f, stiffness = 500f)
                )) togetherWith (slideOutVertically(
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)
                ) { -it / 3 } + fadeOut(
                    spring(dampingRatio = 0.7f, stiffness = 500f)
                ))
            }
        },
        label = "readReceipt"
    ) { targetState ->
        val color = when (targetState) {
            2 -> OnlineGreen
            else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        }

        val scale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
            label = "receiptScale"
        )

        when (targetState) {
            0 -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.message_status_sent),
                tint = color,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            )
            1 -> Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = stringResource(R.string.message_status_delivered),
                tint = color,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            )
            2 -> Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = stringResource(R.string.message_status_read),
                tint = color,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            )
        }
    }
}
