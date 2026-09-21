package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightSurface
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.group.GroupMemberUi
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.UnreadRed
import java.text.SimpleDateFormat
import java.util.Date

object GroupMemberSectionUtils {
    @Composable
    fun roleLabel(role: String): String = when (role) {
        "OWNER" -> stringResource(R.string.group_detail_role_owner)
        "ADMIN" -> stringResource(R.string.group_detail_role_admin)
        else -> stringResource(R.string.group_detail_role_member)
    }

    fun roleRank(role: String): Int = when (role) {
        "OWNER" -> 0
        "ADMIN" -> 1
        else -> 2
    }

    @Composable
    fun formatMuteTime(timestamp: Long): String {
        if (timestamp <= 0) return stringResource(R.string.group_detail_not_muted)
        val formatter = SimpleDateFormat(
            "MM-dd HH:mm",
            LocalConfiguration.current.locales[0]
        )
        return formatter.format(Date(timestamp))
    }

    // G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
    @Composable
    internal fun highlightedText(text: String, query: String): AnnotatedString {
        val (c, bg) = SearchHighlightAccent
        return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
    }
}

@Composable
fun roleLabel(role: String): String = GroupMemberSectionUtils.roleLabel(role)

fun roleRank(role: String): Int = GroupMemberSectionUtils.roleRank(role)

@Composable
fun formatMuteTime(timestamp: Long): String = GroupMemberSectionUtils.formatMuteTime(timestamp)

@Composable
fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString =
    GroupMemberSectionUtils.highlightedText(text, query)

@Composable
fun MemberRow(
    member: GroupMemberUi,
    isMe: Boolean,
    canManage: Boolean,
    isOwner: Boolean,
    isUpdating: Boolean,
    onSetTitle: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onTransferOwnership: () -> Unit,
    onMute: () -> Unit,
    onRemove: () -> Unit,
    onOpenProfile: () -> Unit = {},
    highlightQuery: String = ""
) {
    var actionsExpanded by remember(member.userId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isMe) {
            Avatar(
                name = member.name,
                avatarUrl = member.avatar,
                size = AvatarSize.SM,
                isOnline = member.isOnline,
                modifier = Modifier.clickable { onOpenProfile() }
            )
        } else {
            Avatar(name = member.name, avatarUrl = member.avatar, size = AvatarSize.SM, isOnline = member.isOnline)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).then(if (!isMe) Modifier.clickable { onOpenProfile() } else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(member.displayName)
                    else highlightedText(member.displayName, highlightQuery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isMe) Text(stringResource(R.string.group_detail_me_marker), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                listOfNotNull(
                    roleLabel(member.role),
                    member.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.group_detail_no_title),
                    if (member.isMuted && member.role != "OWNER") stringResource(R.string.group_detail_muted_until, formatMuteTime(member.mutedUntil)) else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (member.isMuted && member.role != "OWNER") UnreadRed else LocalChatPalette.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canManage) {
            Box {
                IconButton(onClick = { actionsExpanded = true }, enabled = !isUpdating) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.group_detail_member_actions, member.displayName),
                        tint = LocalChatPalette.current.textSecondary
                    )
                }
                DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.group_detail_set_title)) },
                        onClick = { actionsExpanded = false; onSetTitle() },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) }
                    )
                    if (isOwner && !isMe && member.role != "OWNER") {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.role == "ADMIN") stringResource(R.string.group_detail_demote)
                                    else stringResource(R.string.group_detail_promote_admin)
                                )
                            },
                            onClick = {
                                actionsExpanded = false
                                if (member.role == "ADMIN") onDemote() else onPromote()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_detail_transfer_owner), color = LocalChatPalette.current.unreadRed) },
                            onClick = { actionsExpanded = false; onTransferOwnership() },
                            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = LocalChatPalette.current.unreadRed) }
                        )
                    }
                    if (!isMe && member.role != "OWNER" && (isOwner || member.role != "ADMIN")) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.isMuted) stringResource(R.string.group_detail_unmute)
                                    else stringResource(R.string.group_detail_mute)
                                )
                            },
                            onClick = { actionsExpanded = false; onMute() }
                        )
                    }
                    if (!isMe && member.role != "OWNER" && (isOwner || member.role != "ADMIN")) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_remove), color = LocalChatPalette.current.unreadRed) },
                            onClick = { actionsExpanded = false; onRemove() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CandidateRow(user: User, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (user.status.isNotBlank()) Text(user.status, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onAdd, enabled = enabled) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.chat_add))
        }
    }
}

@Composable
fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalChatPalette.current.textHint,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)
    )
}
