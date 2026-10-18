package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.ui.screen.chatdetail.group.GroupAnnouncementCard
import com.maodouchat.ui.screen.chatdetail.group.GroupFeatureAction
import com.maodouchat.ui.screen.chatdetail.group.GroupFeaturesCard
import com.maodouchat.ui.screen.chatdetail.group.GroupHeader
import com.maodouchat.ui.screen.chatdetail.group.SectionTitle
import com.maodouchat.ui.theme.LocalChatPalette

// 群详情页总览区（头部 + 公告 + 频道提示 + 功能入口）：原 GroupDetailScreen 内联 lambda，纯搬移。
@Composable
internal fun GroupDetailOverview(
    state: GroupDetailUiState,
    chatId: String,
    onShowAvatarFull: () -> Unit,
    onShowBotPicker: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenGroupPoll: (String) -> Unit,
    onOpenGroupCheckin: (String) -> Unit,
    onOpenGroupChain: (String) -> Unit,
    onOpenGroupPk: (String) -> Unit,
) {
    GroupHeader(
        groupName = state.groupName,
        groupAvatar = state.groupAvatar,
        memberCount = state.members.size,
        myRole = state.myRole,
        onShowAvatarFull = onShowAvatarFull
    )
    GroupAnnouncementCard(state.groupAnnouncement)
    if (state.isChannel) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                Icons.Outlined.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (state.canManageGroup) {
                    pluralStringResource(
                        R.plurals.chat_channel_member_count,
                        state.members.size,
                        state.members.size
                    )
                } else {
                    stringResource(R.string.chat_channel_subscriber_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                modifier = Modifier.weight(1f)
            )
        }
    }
    SectionTitle(stringResource(R.string.group_detail_play))
    GroupFeaturesCard(
        actions = buildList {
            add(GroupFeatureAction(stringResource(R.string.chat_group_ai_title), Icons.Outlined.AutoAwesome) { onOpenAgent() })
            add(GroupFeatureAction(stringResource(R.string.group_play_poll), Icons.Outlined.Checklist) { onOpenGroupPoll(chatId) })
            add(GroupFeatureAction(stringResource(R.string.group_play_checkin), Icons.Outlined.History) { onOpenGroupCheckin(chatId) })
            add(GroupFeatureAction(stringResource(R.string.group_play_chain_title), Icons.Outlined.Link) { onOpenGroupChain(chatId) })
            add(GroupFeatureAction(stringResource(R.string.group_play_pk_title), Icons.Outlined.SwapHoriz) { onOpenGroupPk(chatId) })
            if (state.canManageGroup && !state.isChannel) {
                add(GroupFeatureAction(stringResource(R.string.group_play_invite_bot), Icons.Outlined.PersonAdd) { onShowBotPicker() })
            }
        }
    )
}
