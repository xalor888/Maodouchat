package com.maodouchat.ui.screen.contacts

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.remember
import com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightSurface
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.FloatingBottomBarContentPadding
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 通讯录的「搜索与好友请求」一族（G133 从 `ContactsScreen.kt` 拆出，原 211 行）。
 *
 * 四个声明：`SearchResultList`（搜索结果列表）、`SearchUserRow`（单个搜索结果行：
 * 头像/昵称/账号/已好友态/添加按钮）、`FriendRequestRow`（待处理好友请求行：
 * 接受/拒绝）、`GroupInviteRow`（群邀请行：接受/拒绝）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
internal fun SearchResultList(
    results: List<User>,
    isSearching: Boolean,
    searchFailed: Boolean,
    query: String,
    friendIds: Set<String>,
    pendingOutgoingIds: Set<String>,
    onOpenChat: (User) -> Unit,
    onAddFriend: (User) -> Unit
) {
    val motion = LocalMotionSettings.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (isSearching) stringResource(R.string.contacts_searching, query) else pluralStringResource(R.plurals.contacts_search_results, results.size, query, results.size),
            style = MaterialTheme.typography.labelMedium,
            color = LocalChatPalette.current.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (isSearching && results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
            return
        }
        if (results.isEmpty()) {
            EmptyState(
                type = EmptyStateType.SEARCH,
                title = stringResource(
                    if (searchFailed) R.string.contacts_search_failed
                    else R.string.contacts_no_matching_users
                ),
                modifier = Modifier.fillMaxSize().padding(32.dp)
            )
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FloatingBottomBarContentPadding)
        ) {
            items(results, key = { it.id }, contentType = { "contact_result" }) { user ->
                Column(modifier = Modifier.animateItem(
                    fadeInSpec = motion.listItemFadeInSpec(),
                    fadeOutSpec = motion.listItemFadeOutSpec(),
                    placementSpec = motion.listItemPlacementSpec()
                )) {
                SearchUserRow(
                    user = user,
                    highlightQuery = query,
                    isFriend = user.id in friendIds,
                    requestPending = user.id in pendingOutgoingIds,
                    onOpenChat = { onOpenChat(user) },
                    onAddFriend = { onAddFriend(user) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 80.dp))
                }
            }
            item(key = "search_footer", contentType = "footer") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
internal fun SearchUserRow(
    user: User,
    onOpenChat: () -> Unit,
    onAddFriend: () -> Unit,
    highlightQuery: String = "",
    isFriend: Boolean = false,
    requestPending: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChat)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.MD, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(user.displayName)
                else highlightedText(user.displayName, highlightQuery),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (user.status.isNotBlank()) {
                // 预设状态 wire 值是中文原文，必须经 localizedCustomStatusLabel 本地化展示
                Text(
                    com.maodouchat.ui.component.localizedCustomStatusLabel(user.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1
                )
            }
        }
        when {
            isFriend -> {
                TextButton(onClick = onOpenChat) {
                    Text(stringResource(R.string.contacts_start_chat), color = MaterialTheme.colorScheme.primary)
                }
            }
            requestPending -> {
                Text(
                    stringResource(R.string.contacts_friend_request_pending),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary
                )
            }
            else -> {
                TextButton(onClick = onAddFriend) {
                    Text(stringResource(R.string.contacts_add_friend), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
internal fun FriendRequestRow(
    request: FriendRequestItem,
    onAccept: (() -> Unit)?,
    onReject: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
    // 1.297：长按拉黑请求者（拦截骚扰）
    onBlock: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onBlock != null) Modifier.combinedClickable(onClick = {}, onLongClick = onBlock)
                else Modifier.clickable(enabled = false) {}
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Avatar(name = request.user.name, avatarUrl = request.user.avatar, size = AvatarSize.MD, isOnline = request.user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(request.user.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (request.message.isNotBlank()) {
                Text(request.message, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary, maxLines = 2)
            } else if (request.outgoing) {
                Text(stringResource(R.string.contacts_friend_request_pending), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
        }
        if (onAccept != null && onReject != null) {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.contacts_friend_reject), color = LocalChatPalette.current.textSecondary)
            }
            Button(onClick = onAccept, modifier = Modifier.padding(start = 4.dp)) {
                Text(stringResource(R.string.contacts_friend_accept))
            }
        } else if (onCancel != null) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.contacts_friend_cancel), color = LocalChatPalette.current.textSecondary)
            }
        }
    }
}

/**
 * 9.3xx：待处理群邀请行——本人接受后才成为群成员。
 */
@Composable
internal fun GroupInviteRow(
    invite: GroupInviteItem,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                invite.chatName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                stringResource(R.string.contacts_group_invite_row_subtitle, invite.inviterName, invite.memberCount),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 1
            )
        }
        TextButton(onClick = onDecline, enabled = !busy) {
            Text(stringResource(R.string.contacts_group_invite_decline), color = LocalChatPalette.current.textSecondary)
        }
        Button(onClick = onAccept, enabled = !busy, modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(R.string.contacts_group_invite_accept))
        }
    }
}

// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
private fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
