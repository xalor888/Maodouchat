package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.domain.groups.GroupAuditActionLabelKind
import com.maodouchat.domain.groups.groupAuditActionLabelKind
import com.maodouchat.domain.messaging.ChatDateDisplay
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
fun GroupAuditRow(audit: GroupAuditLogDto) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.group_detail_audit_event,
                    audit.actorName.ifBlank { audit.actorId },
                    groupAuditActionLabel(audit.action),
                    audit.targetUserName?.takeIf(String::isNotBlank) ?: audit.targetUserId.orEmpty()
                ),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                ChatDateDisplay.formatMonthDayTime(audit.createdAt, locale),
                color = LocalChatPalette.current.textHint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun groupAuditActionLabel(action: String): String = stringResource(
    when (groupAuditActionLabelKind(action)) {
        GroupAuditActionLabelKind.MemberAdded -> R.string.group_audit_member_added
        GroupAuditActionLabelKind.MemberJoined -> R.string.group_audit_member_joined
        GroupAuditActionLabelKind.MemberRemoved -> R.string.group_audit_member_removed
        GroupAuditActionLabelKind.MemberPromoted -> R.string.group_audit_member_promoted
        GroupAuditActionLabelKind.MemberDemoted -> R.string.group_audit_member_demoted
        GroupAuditActionLabelKind.MemberMuted -> R.string.group_audit_member_muted
        GroupAuditActionLabelKind.MemberUnmuted -> R.string.group_audit_member_unmuted
        GroupAuditActionLabelKind.GroupRenamed -> R.string.group_audit_group_renamed
        GroupAuditActionLabelKind.AnnouncementUpdated -> R.string.group_audit_announcement
        GroupAuditActionLabelKind.AvatarUpdated -> R.string.group_audit_avatar
        GroupAuditActionLabelKind.Invite -> R.string.group_audit_invite
        GroupAuditActionLabelKind.TitleUpdated -> R.string.group_audit_title
        GroupAuditActionLabelKind.NicknameUpdated -> R.string.group_audit_nickname
        GroupAuditActionLabelKind.MemberLeft -> R.string.group_audit_member_left
        GroupAuditActionLabelKind.OwnershipTransferred -> R.string.group_audit_ownership_transferred
        GroupAuditActionLabelKind.Other -> R.string.group_audit_other
    }
)
