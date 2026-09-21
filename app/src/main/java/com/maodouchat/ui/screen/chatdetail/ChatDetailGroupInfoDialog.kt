package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Secondary

/**
 * 群信息对话框（G77 从 `ChatDetailRoute.kt` 拆出，原 190 行）。
 *
 * 覆盖群名编辑、成员搜索/分页（每页 20）、候选人搜索/分页（每页 20）、
 * 移除成员、添加成员等群管理动作。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所有输入经参数显式传入。块内三个 `groupInfo*` 状态原本就是块内 `remember`，
 * 搬移后所有权随之进来，Route 不再持有它们。纯搬移，不改判断。
 *
 * @param onDismiss 关闭（原 `onDismiss()`，并顺带清空三个块内状态）
 */
@Composable
internal fun ChatDetailGroupInfoDialog(
    chat: Chat?,
    currentUserId: String,
    groupCandidates: List<User>,
    isUpdatingGroup: Boolean,
    onDismiss: () -> Unit,
    onRenameGroup: (String) -> Unit,
    onAddGroupMember: (String) -> Unit,
    onRemoveGroupMember: (String) -> Unit,
) {
    val state = ChatDetailUiState(
        chat = chat,
        currentUserId = currentUserId,
        groupCandidates = groupCandidates,
        isUpdatingGroup = isUpdatingGroup,
    )
    var groupNameDraft by remember(chat?.id, chat?.groupName) { mutableStateOf(chat?.groupName.orEmpty()) }
    var groupInfoSearch by remember(chat?.id) { mutableStateOf("") }
    var groupInfoSearchExpanded by remember(chat?.id) { mutableStateOf(false) }
    var groupInfoCandidatesExpanded by remember(chat?.id) { mutableStateOf(false) }
    val groupInfoCandidatePage = 20
    AlertDialog(
        onDismissRequest = {
            onDismiss()
            groupInfoSearch = ""
            groupInfoSearchExpanded = false
            groupInfoCandidatesExpanded = false
        },
        title = { Text(chat?.groupName ?: stringResource(R.string.chat_group)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val members = chat?.participants.orEmpty()
                val q = groupInfoSearch.trim()
                val filteredMembers = if (q.isEmpty()) {
                    members
                } else {
                    members.filter { m ->
                        m.displayName.contains(q, ignoreCase = true) ||
                            m.name.contains(q, ignoreCase = true) ||
                            m.id.contains(q, ignoreCase = true) ||
                            m.status.contains(q, ignoreCase = true)
                    }
                }
                val filteredCandidates = if (q.isEmpty()) {
                    state.groupCandidates
                } else {
                    state.groupCandidates.filter { u ->
                        u.displayName.contains(q, ignoreCase = true) ||
                            u.name.contains(q, ignoreCase = true) ||
                            u.id.contains(q, ignoreCase = true)
                    }
                }
                val visibleCandidates = if (groupInfoCandidatesExpanded || filteredCandidates.size <= groupInfoCandidatePage) {
                    filteredCandidates
                } else {
                    filteredCandidates.take(groupInfoCandidatePage)
                }
                TextField(
                    value = groupNameDraft,
                    onValueChange = { groupNameDraft = it.take(50) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_group_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedIndicatorColor = Primary,
                        unfocusedIndicatorColor = Outline,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    )
                )
                TextButton(
                    onClick = { onRenameGroup(groupNameDraft) },
                    enabled = !state.isUpdatingGroup && groupNameDraft.trim().isNotBlank() && groupNameDraft.trim() != chat?.groupName.orEmpty()
                ) {
                    if (state.isUpdatingGroup) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(R.string.chat_save_group_name))
                }
                if (members.size + state.groupCandidates.size >= 4) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(
                            onClick = {
                                groupInfoSearchExpanded = !groupInfoSearchExpanded
                                if (!groupInfoSearchExpanded) groupInfoSearch = ""
                            }
                        ) {
                            Icon(
                                imageVector = if (groupInfoSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(
                                    if (groupInfoSearchExpanded) R.string.chat_search_close else R.string.chat_search_action
                                ),
                                tint = Primary,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = groupInfoSearchExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        OutlinedTextField(
                            value = groupInfoSearch,
                            onValueChange = { groupInfoSearch = it.take(100) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_group_info_search_hint)) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Secondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Outline,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                cursorColor = Primary
                            )
                        )
                    }
                }
                Text(pluralStringResource(R.plurals.chat_members_count, members.size, members.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (filteredMembers.isEmpty() && q.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chat_group_info_search_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                } else {
                    filteredMembers.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Avatar(name = member.name, size = AvatarSize.SM)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (member.status.isNotBlank()) Text(member.status, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (member.id == state.currentUserId) Text(stringResource(R.string.chat_me), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            else TextButton(
                                enabled = !state.isUpdatingGroup,
                                onClick = { onRemoveGroupMember(member.id) }
                            ) { Text(stringResource(R.string.chat_remove), color = LocalChatPalette.current.unreadRed) }
                        }
                    }
                }
                if (state.groupCandidates.isNotEmpty()) {
                    Text(stringResource(R.string.chat_add_member), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (filteredCandidates.isEmpty() && q.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chat_group_info_search_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint
                        )
                    } else {
                        visibleCandidates.forEach { user ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(
                                    enabled = !state.isUpdatingGroup,
                                    onClick = { onAddGroupMember(user.id) }
                                ) { Text(stringResource(R.string.chat_add)) }
                            }
                        }
                        if (!groupInfoCandidatesExpanded && filteredCandidates.size > groupInfoCandidatePage) {
                            TextButton(
                                onClick = { groupInfoCandidatesExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(
                                        R.string.chat_candidates_more,
                                        filteredCandidates.size - groupInfoCandidatePage
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (groupInfoCandidatesExpanded && filteredCandidates.size > groupInfoCandidatePage) {
                            Text(
                                stringResource(R.string.chat_candidates_showing_all, filteredCandidates.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.chat_no_candidates), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                groupInfoSearch = ""
                groupInfoSearchExpanded = false
                groupInfoCandidatesExpanded = false
            }) { Text(stringResource(R.string.common_done)) }
        }
    )}
