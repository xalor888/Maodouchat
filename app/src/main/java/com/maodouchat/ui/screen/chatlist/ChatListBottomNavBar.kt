package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.component.AnimatedBottomNav
import com.maodouchat.ui.component.BottomNavItem
import com.maodouchat.ui.component.LiquidBottomTabItem
import com.maodouchat.ui.component.LiquidBottomTabs
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled

@Composable
fun BottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val unreadTotal by UnreadBadgeStore.totalUnread.collectAsState()
    val exploreBadge by ExploreBadgeStore.count.collectAsState()
    val tabs = listOf(
        LiquidBottomTabItem(
            icon = Icons.Outlined.ChatBubbleOutline,
            selectedIcon = Icons.Filled.ChatBubble,
            label = stringResource(R.string.nav_chats),
            badgeCount = unreadTotal
        ),
        LiquidBottomTabItem(
            icon = Icons.Outlined.Group,
            selectedIcon = Icons.Filled.Group,
            label = stringResource(R.string.nav_contacts)
        ),
        LiquidBottomTabItem(
            icon = Icons.Outlined.Explore,
            selectedIcon = Icons.Filled.Explore,
            label = stringResource(R.string.nav_explore),
            badgeCount = exploreBadge
        ),
        LiquidBottomTabItem(
            icon = Icons.Outlined.Settings,
            selectedIcon = Icons.Filled.Settings,
            label = stringResource(R.string.nav_settings)
        )
    )
    val liquidGlass = LocalLiquidGlassEnabled.current
    val floatingDockOn by com.maodouchat.util.ChromePreferences.floatingDock.collectAsState()
    if (liquidGlass && floatingDockOn) {
        LiquidBottomTabs(
            selectedTabIndex = selectedTab,
            onTabSelected = onTabSelected,
            tabs = tabs,
            modifier = modifier
        )
    } else {
        AnimatedBottomNav(
            selectedIndex = selectedTab,
            onItemSelected = onTabSelected,
            items = tabs.map { tab ->
                BottomNavItem(
                    icon = tab.icon,
                    selectedIcon = tab.selectedIcon,
                    label = tab.label,
                    badgeCount = tab.badgeCount
                )
            },
            modifier = modifier.navigationBarsPadding()
        )
    }
}
