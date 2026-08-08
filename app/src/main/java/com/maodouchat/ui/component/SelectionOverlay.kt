package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary

/**
 * Selection overlay for multi-select mode.
 *
 * Shows a dimmed background + spring-animated checkmark circle.
 * [selected] controls visibility. [modifier] should be placed on each selectable item.
 */
@Composable
fun SelectionOverlay(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotionSettings.current

    AnimatedVisibility(
        visible = selected,
        modifier = modifier.fillMaxSize(),
        enter = if (!motion.animationsEnabled) {
            fadeIn()
        } else {
            fadeIn(spring(dampingRatio = 0.6f, stiffness = 300f))
        },
        exit = if (!motion.animationsEnabled) {
            fadeOut()
        } else {
            fadeOut(spring(dampingRatio = 0.7f, stiffness = 400f))
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Dimming overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Primary.copy(alpha = 0.12f))
            )

            // Spring checkmark
            val checkScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 500f
                ),
                label = "checkScale"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 8.dp, y = 8.dp)
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                        alpha = checkScale.coerceIn(0f, 1f)
                    }
                    .clip(CircleShape)
                    .background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.common_selected),
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Action toolbar that slides in at the top during multi-select mode.
 *
 * [visible] controls the toolbar. [selectedCount] shows how many items are selected.
 * [content] is the toolbar actions (delete, forward, etc.).
 */
@Composable
fun SelectionToolbar(
    visible: Boolean,
    selectedCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val motion = LocalMotionSettings.current

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (!motion.animationsEnabled) {
            fadeIn()
        } else {
            slideInVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
            ) { -it } + fadeIn(
                spring(dampingRatio = 0.7f, stiffness = 400f)
            )
        },
        exit = if (!motion.animationsEnabled) {
            fadeOut()
        } else {
            slideOutVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
            ) { -it } + fadeOut(
                spring(dampingRatio = 0.7f, stiffness = 400f)
            )
        }
    ) {
        content()
    }
}
