package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R

// 群详情页签枚举：原 GroupDetailScreen 私有声明，同包 internal 化后随页签区一起搬出。
internal enum class GroupDetailTab(val mediaCategory: MediaCenterCategory? = null) {
    MEMBERS,
    AUDIT,
    MEDIA(MediaCenterCategory.MEDIA),
    FILES(MediaCenterCategory.FILES),
    VOICE(MediaCenterCategory.VOICE),
    LOCATION(MediaCenterCategory.LOCATION),
    LINKS(MediaCenterCategory.LINKS),
}

internal fun GroupDetailTab.labelResource(): Int = when (this) {
    GroupDetailTab.MEMBERS -> R.string.chat_group_member
    GroupDetailTab.AUDIT -> R.string.group_detail_audit_title
    GroupDetailTab.MEDIA -> R.string.media_center_media
    GroupDetailTab.FILES -> R.string.media_center_files
    GroupDetailTab.VOICE -> R.string.media_center_voice
    GroupDetailTab.LOCATION -> R.string.media_center_location
    GroupDetailTab.LINKS -> R.string.media_center_links
}

// 群详情页签行：原 GroupDetailScreen 内联 lambda，纯搬移。
@Composable
internal fun GroupDetailTabs(
    selectedTab: GroupDetailTab,
    onSelectTab: (GroupDetailTab) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTab.ordinal,
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)) },
    ) {
        GroupDetailTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = { Text(stringResource(tab.labelResource()), maxLines = 1) },
            )
        }
    }
}
