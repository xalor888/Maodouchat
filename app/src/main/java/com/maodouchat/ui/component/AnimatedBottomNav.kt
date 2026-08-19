package com.maodouchat.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint

data class BottomNavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val badgeCount: Int = 0
)

/**
 * Animated bottom navigation bar with a Telegram-style sliding selection pill.
 *
 * A single rounded pill springs horizontally to the selected tab (instead of
 * appearing/disappearing per tab). Icon scale, tint and label size animate
 * with the unified motion specs and collapse when animations are disabled.
 */
@Composable
fun AnimatedBottomNav(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotionSettings.current
    val sidePadding = 8.dp
    val pillWidth = 48.dp
    val pillHeight = 28.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val tabCount = items.size.coerceAtLeast(1)
        val tabWidth = (maxWidth - sidePadding * 2) / tabCount
        val targetPillOffset = sidePadding + tabWidth * selectedIndex + (tabWidth - pillWidth) / 2

        val pillOffset by animateDpAsState(
            targetValue = targetPillOffset,
            animationSpec = if (motion.animationsEnabled) {
                spring(dampingRatio = 0.8f, stiffness = 380f)
            } else {
                snap()
            },
            label = "navPillOffset"
        )

        // Sliding selection pill (drawn behind the items). When motion is off the
        // offset snaps, so the pill still marks the selected tab without animating.
        if (items.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(pillOffset.roundToPx(), 0) }
                    .size(width = pillWidth, height = pillHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex

                val scale by animateFloatAsState(
                    targetValue = if (isSelected && motion.animationsEnabled) 1.1f else 1f,
                    animationSpec = motion.springSpec(
                        dampingRatio = 0.6f,
                        stiffness = 400f
                    ),
                    label = "navScale$index"
                )

                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) Primary else TextHint,
                    animationSpec = if (motion.animationsEnabled) {
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    } else {
                        snap()
                    },
                    label = "navTint$index"
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onItemSelected(index)
                        }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = iconTint,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                        )
                    }

                    // Label with animated size
                    val labelSize by animateFloatAsState(
                        targetValue = if (isSelected) 11f else 10f,
                        animationSpec = motion.springSpec(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "labelSize$index"
                    )

                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = labelSize.sp,
                            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold
                            else androidx.compose.ui.text.font.FontWeight.Normal
                        ),
                        color = if (isSelected) Primary else TextHint,
                        maxLines = 1
                    )

                    // Badge
                    if (item.badgeCount > 0) {
                        AnimatedBadge(
                            count = item.badgeCount,
                            modifier = Modifier.offset(y = (-2).dp)
                        )
                    }
                }
            }
        }
    }
}
