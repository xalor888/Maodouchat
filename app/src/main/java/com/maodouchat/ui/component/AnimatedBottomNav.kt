package com.maodouchat.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings

data class BottomNavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val badgeCount: Int = 0
)

/** 贴底导航与悬浮胶囊共用的控件尺寸，关掉悬浮底栏后按钮不应被撑大。 */
internal object PinnedBottomNavMetrics {
    val BarHeight = 64.dp
    val IconSize = 22.dp
    val LabelSize = 10.sp
    val PillHeight = 56.dp
    val SidePadding = 8.dp
    val PillInset = 4.dp
}

/**
 * Pinned bottom navigation. Geometry matches the floating capsule dock
 * (64dp bar, 22dp icons, 10sp labels) so turning the dock off does not
 * inflate the tab buttons.
 */
@Composable
fun AnimatedBottomNav(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotionSettings.current
    val selectedTint = MaterialTheme.colorScheme.primary
    val unselectedTint = LocalChatPalette.current.textHint
    val sidePadding = PinnedBottomNavMetrics.SidePadding

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(PinnedBottomNavMetrics.BarHeight)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val tabCount = items.size.coerceAtLeast(1)
        val tabWidth = (maxWidth - sidePadding * 2) / tabCount
        val pillWidth = (tabWidth - PinnedBottomNavMetrics.PillInset * 2).coerceAtLeast(32.dp)
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

        if (items.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(pillOffset.roundToPx(), 0) }
                    .size(width = pillWidth, height = PinnedBottomNavMetrics.PillHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = sidePadding),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex

                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) selectedTint else unselectedTint,
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
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onItemSelected(index)
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    Box {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = iconTint,
                            modifier = Modifier.size(PinnedBottomNavMetrics.IconSize)
                        )
                        if (item.badgeCount > 0) {
                            AnimatedNotificationBadge(
                                count = item.badgeCount,
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                        }
                    }

                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = PinnedBottomNavMetrics.LabelSize,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = 0.sp
                        ),
                        color = if (isSelected) selectedTint else unselectedTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
