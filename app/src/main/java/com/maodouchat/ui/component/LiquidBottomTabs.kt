/*
 * 悬浮胶囊底栏 — 几何与交互抄自 Murexide LiquidBottomTabs
 *（reference/Murexide/.../liquidglass/LiquidNavigation.kt）。
 * 原始 AndroidLiquidGlass catalog Copyright 2025 Kyant, Apache License 2.0。
 */
package com.maodouchat.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.liquidglass.LiquidBottomTabs as MurexideLiquidBottomTabs
import com.maodouchat.ui.theme.liquidglass.snapNavigationIndex as murexideSnapNavigationIndex

/** 一级页列表滚过悬浮底栏所需的底部留白（64 胶囊 + 外边距 + 导航条余量）。 */
val FloatingBottomBarContentPadding = 128.dp

data class LiquidBottomTabItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val label: String,
    val badgeCount: Int = 0
)

internal fun snapNavigationIndex(value: Float, tabsCount: Int): Int =
    murexideSnapNavigationIndex(value, tabsCount)

internal fun liquidGlassContainerColor(isLightTheme: Boolean): Color {
    return if (isLightTheme) {
        Color(0xFFFAFAFA).copy(alpha = 0.28f)
    } else {
        Color(0xFF121212).copy(alpha = 0.32f)
    }
}

internal fun liquidGlassSelectedPillColor(isLightTheme: Boolean): Color {
    return if (isLightTheme) {
        Color.Black.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }
}

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    tabs: List<LiquidBottomTabItem>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
) {
    if (tabs.isEmpty()) return
    val tabsCount = tabs.size
    val selectedIndex = selectedTabIndex.coerceIn(0, tabsCount - 1)
    val glassBackdrop = backdrop
    if (glassBackdrop == null) return

    MurexideLiquidBottomTabs(
        selectedTabIndex = selectedIndex,
        onTabSelected = onTabSelected,
        backdrop = glassBackdrop,
        tabsCount = tabsCount,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 10.dp),
    ) { index, selected, _ ->
        val item = tabs[index]
        val inherited = LocalContentColor.current
        val contentColor by animateColorAsState(
            targetValue = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                inherited
            },
            label = "liquid tab content"
        )
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                Icon(
                    imageVector = if (selected) item.selectedIcon else item.icon,
                    contentDescription = item.label,
                    tint = contentColor,
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
                color = contentColor,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = 0.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
