/*
 * 悬浮胶囊底栏 — 几何与交互抄自 Murexide LiquidBottomTabs
 *（reference/Murexide/.../liquidglass/LiquidNavigation.kt）。
 * 原始 AndroidLiquidGlass catalog Copyright 2025 Kyant, Apache License 2.0。
 * 不引入 com.kyant.backdrop / RuntimeShader：半透明胶囊 + 内高光/内底影 + 滑动选中 pill + 拖拽切 tab。
 */
package com.maodouchat.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.liquidPress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 一级页列表滚过悬浮底栏所需的底部留白（64 胶囊 + 外边距 + 导航条余量）。 */
val FloatingBottomBarContentPadding = 96.dp

data class LiquidBottomTabItem(
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int = 0
)

internal fun snapNavigationIndex(value: Float, tabsCount: Int): Int {
    require(tabsCount > 0) { "tabsCount must be positive" }
    return value.roundToInt().coerceIn(0, tabsCount - 1)
}

internal fun liquidGlassContainerColor(isLightTheme: Boolean): Color {
    return if (isLightTheme) {
        Color(0xFFFAFAFA).copy(alpha = 0.72f)
    } else {
        Color(0xFF121212).copy(alpha = 0.72f)
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
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val tabsCount = tabs.size
    val selectedIndex = selectedTabIndex.coerceIn(0, tabsCount - 1)
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val containerColor = liquidGlassContainerColor(isLightTheme)
    val pillColor = liquidGlassSelectedPillColor(isLightTheme)
    val borderAlphaTop = if (isLightTheme) 0.55f else 0.28f
    val dragValue = remember { Animatable(selectedIndex.toFloat()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedIndex) {
        if (kotlin.math.abs(dragValue.value - selectedIndex) > 0.01f) {
            dragValue.animateTo(
                selectedIndex.toFloat(),
                spring(dampingRatio = 0.85f, stiffness = 380f)
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(18.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = borderAlphaTop),
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = if (isLightTheme) 0.18f else 0.10f)
                    )
                ),
                shape = CircleShape
            )
            .liquidPress(pressedScale = 0.985f, highlightAlpha = 0.08f)
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / tabsCount
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * dragValue.value,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 380f
            ),
            label = "liquid tab indicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .height(56.dp)
                .clip(CircleShape)
                .background(pillColor)
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (isLightTheme) 0.22f else 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = if (isLightTheme) 0.05f else 0.12f)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(tabsCount, tabWidth) {
                    val widthPx = tabWidth.toPx().coerceAtLeast(1f)
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = snapNavigationIndex(dragValue.value, tabsCount)
                            scope.launch {
                                dragValue.animateTo(
                                    target.toFloat(),
                                    spring(dampingRatio = 0.85f, stiffness = 380f)
                                )
                            }
                            onTabSelected(target)
                        },
                        onDragCancel = {
                            val target = snapNavigationIndex(dragValue.value, tabsCount)
                            scope.launch {
                                dragValue.animateTo(
                                    target.toFloat(),
                                    spring(dampingRatio = 0.85f, stiffness = 380f)
                                )
                            }
                        }
                    ) { _, dragAmount ->
                        val next = (dragValue.value + dragAmount / widthPx)
                            .coerceIn(0f, (tabsCount - 1).toFloat())
                        scope.launch { dragValue.snapTo(next) }
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "liquid tab content"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onTabSelected(index) }
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = contentColor
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
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
