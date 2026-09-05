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
import com.maodouchat.network.GroupAuditLogDto
import com.maodouchat.ui.theme.LocalChatPalette
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun GroupAuditRow(audit: GroupAuditLogDto) {
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
                SimpleDateFormat(
                    "MM-dd HH:mm",
                    LocalConfiguration.current.locales[0]
                ).format(Date(audit.createdAt)),
                color = LocalChatPalette.current.textHint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun groupAuditActionLabel(action: String): String = stringResource(
    when (action) {
        "MEMBER_ADDED" -> R.string.group_audit_member_added
        "MEMBER_JOINED" -> R.string.group_audit_member_joined
        "MEMBER_REMOVED" -> R.string.group_audit_member_removed
        "MEMBER_PROMOTED" -> R.string.group_audit_member_promoted
        "MEMBER_DEMOTED" -> R.string.group_audit_member_demoted
        "MEMBER_MUTED" -> R.string.group_audit_member_muted
        "MEMBER_UNMUTED" -> R.string.group_audit_member_unmuted
        "GROUP_RENAMED" -> R.string.group_audit_group_renamed
        "ANNOUNCEMENT_UPDATED" -> R.string.group_audit_announcement
        "AVATAR_UPDATED" -> R.string.group_audit_avatar
        "INVITE_ROTATED", "INVITE_CONFIGURED" -> R.string.group_audit_invite
        "TITLE_UPDATED" -> R.string.group_audit_title
        "NICKNAME_UPDATED" -> R.string.group_audit_nickname
        "MEMBER_LEFT" -> R.string.group_audit_member_left
        "OWNERSHIP_TRANSFERRED" -> R.string.group_audit_ownership_transferred
        else -> R.string.group_audit_other
    }
)

/** Non-composable tokens so audit search matches common zh/en action labels without Context. */
fun groupAuditActionSearchTokens(action: String): List<String> = when (action) {
    "MEMBER_ADDED" -> listOf("添加", "成员", "added", "member", "add")
    "MEMBER_JOINED" -> listOf("加入", "邀请", "joined", "invite", "join")
    "MEMBER_REMOVED" -> listOf("移除", "踢出", "removed", "remove", "kick")
    "MEMBER_PROMOTED" -> listOf("管理员", "提升", "admin", "promoted", "promote")
    "MEMBER_DEMOTED" -> listOf("取消管理员", "降级", "demoted", "demote")
    "MEMBER_MUTED" -> listOf("禁言", "muted", "mute")
    "MEMBER_UNMUTED" -> listOf("解禁", "解除禁言", "unmuted", "unmute")
    "GROUP_RENAMED" -> listOf("群名", "改名", "rename", "renamed")
    "ANNOUNCEMENT_UPDATED" -> listOf("公告", "announcement")
    "AVATAR_UPDATED" -> listOf("头像", "avatar")
    "INVITE_ROTATED", "INVITE_CONFIGURED" -> listOf("邀请", "链接", "invite", "link")
    "TITLE_UPDATED" -> listOf("头衔", "title")
    "NICKNAME_UPDATED" -> listOf("昵称", "nickname")
    "MEMBER_LEFT" -> listOf("退群", "退出", "left", "leave")
    "OWNERSHIP_TRANSFERRED" -> listOf("转让", "群主", "owner", "transfer")
    else -> listOf("操作", "activity", "group")
}
