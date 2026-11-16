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
import com.maodouchat.core.model.GroupDetailTab
import com.maodouchat.domain.groups.GroupDetailTabKind
import com.maodouchat.domain.groups.groupDetailTabKind

internal fun GroupDetailTab.labelResource(): Int = when (groupDetailTabKind(this)) {
    GroupDetailTabKind.MEMBERS -> R.string.chat_group_member
    GroupDetailTabKind.AUDIT -> R.string.group_detail_audit_title
    GroupDetailTabKind.MEDIA -> R.string.media_center_media
    GroupDetailTabKind.FILES -> R.string.media_center_files
    GroupDetailTabKind.VOICE -> R.string.media_center_voice
    GroupDetailTabKind.LOCATION -> R.string.media_center_location
    GroupDetailTabKind.LINKS -> R.string.media_center_links
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
