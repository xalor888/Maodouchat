package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.GroupCallPolicy

/**
 * 群通话成员选择对话框（G78 从 `ChatDetailRoute.kt` 拆出，原 123 行）。
 *
 * 覆盖候选人过滤（显示名 / userId）、`GroupCallPolicy.MAX_MESH_MEMBERS - 1` 上限、
 * 多选、搜索、确认发起。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所有输入经参数显式传入。纯搬移，不改判断。
 *
 * 三个 `rememberSaveable` 开关的所有权**留在 Route**（打开入口也在那里用），
 * 因此这里以「值 + setter」形式传入，而不是把状态搬进来。
 *
 * @param pendingCallType 待发起的通话类型（null 表示尚未选择）
 * @param selectedMemberIds 已勾选的成员
 * @param memberQuery 搜索词
 */
@Composable
internal fun ChatDetailGroupCallMemberDialog(
    chat: Chat?,
    currentUserId: String,
    pendingCallType: CallType?,
    selectedMemberIds: Set<String>,
    memberQuery: String,
    onDismiss: () -> Unit,
    onPendingCallTypeChange: (CallType?) -> Unit,
    onSelectedMemberIdsChange: (Set<String>) -> Unit,
    onMemberQueryChange: (String) -> Unit,
    onStartGroupCall: (CallType, Set<String>) -> Unit,
) {
    val state = ChatDetailUiState(chat = chat, currentUserId = currentUserId)

    val candidates = state.chat?.participants.orEmpty().filter { it.id != state.currentUserId }
    val maxSelected = com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS - 1
    val memberQuery = memberQuery.trim()
    val filteredCallCandidates = if (memberQuery.isBlank()) {
        candidates
    } else {
        candidates.filter { user ->
            user.displayName.contains(memberQuery, ignoreCase = true) ||
                user.id.contains(memberQuery, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = {
            onDismiss()
            onPendingCallTypeChange(null)
            onSelectedMemberIdsChange(emptySet())
            onMemberQueryChange("")
        },
        title = { Text(stringResource(R.string.call_select_members_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.call_select_members_count, selectedMemberIds.size, maxSelected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.call_group_mesh_limit,
                        com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                if (candidates.size >= 4) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = memberQuery,
                        onValueChange = { onMemberQueryChange(it.take(100)) },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.call_select_members_search_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (filteredCallCandidates.isEmpty()) {
                    Text(
                        stringResource(R.string.call_select_members_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(
                            filteredCallCandidates,
                            key = { _, user -> user.id },
                            contentType = { _, _ -> "group_call_candidate" }
                        ) { _, user ->
                            val selected = user.id in selectedMemberIds
                            val enabled = selected || selectedMemberIds.size < maxSelected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled) {
                                        onSelectedMemberIdsChange(
                                            if (selected) {
                                                selectedMemberIds - user.id
                                            } else {
                                                selectedMemberIds + user.id
                                            }
                                        )
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM)
                                Spacer(Modifier.width(12.dp))
                                Text(user.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Checkbox(
                                    checked = selected,
                                    enabled = enabled,
                                    onCheckedChange = {
                                        onSelectedMemberIdsChange(
                                            if (selected) {
                                                selectedMemberIds - user.id
                                            } else {
                                                selectedMemberIds + user.id
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedMemberIds.isNotEmpty() && pendingCallType != null,
                onClick = {
                    val type = pendingCallType ?: return@TextButton
                    onStartGroupCall(type, selectedMemberIds)
                    onDismiss()
                    onPendingCallTypeChange(null)
                    onSelectedMemberIdsChange(emptySet())
                    onMemberQueryChange("")
                }
            ) {
                Text(stringResource(R.string.call_start_selected_members))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onPendingCallTypeChange(null)
                onSelectedMemberIdsChange(emptySet())
                onMemberQueryChange("")
            }) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )}
