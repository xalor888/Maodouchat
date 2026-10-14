package com.maodouchat.ui.component

import androidx.compose.ui.graphics.vector.ImageVector

data class LiquidBottomTabItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val label: String,
    val badgeCount: Int = 0
)
