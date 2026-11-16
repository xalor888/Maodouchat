package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.core.model.EmptyStateType
import com.maodouchat.ui.component.ShimmerChatRow
import com.maodouchat.domain.conversation.ChatListEmptyKind
import com.maodouchat.domain.conversation.ChatListEmptyStatePolicy
import com.maodouchat.domain.conversation.chatListEmptyKind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

@Composable
internal fun ShimmerChatList() {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(8) { ShimmerChatRow() }
    }
}

@Composable
internal fun EmptyChatState(
    hasSearchQuery: Boolean,
    showArchived: Boolean,
    selectedFolderId: String?,
    loadError: String? = null,
    onRetry: (() -> Unit)? = null,
    onAddContact: () -> Unit,
    onScan: (() -> Unit)?
) {
    // 加载失败且无缓存时显示错误空态，而非「还没有聊天」
    if (ChatListEmptyStatePolicy.isLoadErrorState(loadError, hasSearchQuery, showArchived, selectedFolderId)) {
        EmptyState(
            type = EmptyStateType.NETWORK_ERROR,
            title = stringResource(R.string.chat_load_failed_title),
            subtitle = loadError,
            actionText = stringResource(R.string.chat_load_failed_retry),
            onAction = onRetry
        )
        return
    }
    val emptyKind = chatListEmptyKind(hasSearchQuery, showArchived, selectedFolderId)
    val title = stringResource(when (emptyKind) {
        ChatListEmptyKind.SEARCH -> R.string.chat_empty_search_title
        ChatListEmptyKind.ARCHIVED -> R.string.chat_empty_archived_title
        ChatListEmptyKind.UNREAD -> R.string.chat_folder_empty_unread_title
        ChatListEmptyKind.GROUPS -> R.string.chat_folder_empty_groups_title
        ChatListEmptyKind.DIRECT -> R.string.chat_folder_empty_direct_title
        ChatListEmptyKind.SECRET -> R.string.chat_folder_empty_secret_title
        ChatListEmptyKind.LOCKED -> R.string.chat_folder_empty_locked_title
        ChatListEmptyKind.CUSTOM_FOLDER -> R.string.chat_folder_empty
        ChatListEmptyKind.DEFAULT -> R.string.chat_empty_title
    })
    val subtitle = stringResource(when (emptyKind) {
        ChatListEmptyKind.SEARCH -> R.string.chat_empty_search_subtitle
        ChatListEmptyKind.ARCHIVED -> R.string.chat_empty_archived_subtitle
        ChatListEmptyKind.UNREAD -> R.string.chat_folder_empty_unread_subtitle
        ChatListEmptyKind.GROUPS -> R.string.chat_folder_empty_groups_subtitle
        ChatListEmptyKind.DIRECT -> R.string.chat_folder_empty_direct_subtitle
        ChatListEmptyKind.SECRET -> R.string.chat_folder_empty_secret_subtitle
        ChatListEmptyKind.LOCKED -> R.string.chat_folder_empty_locked_subtitle
        ChatListEmptyKind.CUSTOM_FOLDER -> R.string.chat_folder_empty_subtitle
        ChatListEmptyKind.DEFAULT -> R.string.chat_empty_subtitle
    })
    val showActions = ChatListEmptyStatePolicy.showEmptyActions(hasSearchQuery, showArchived, selectedFolderId)
    EmptyState(
        type = if (hasSearchQuery) EmptyStateType.SEARCH else EmptyStateType.CHAT_LIST,
        title = title,
        subtitle = subtitle,
        actionText = if (showActions) stringResource(R.string.chat_empty_action_add) else null,
        onAction = if (showActions) onAddContact else null,
        secondaryActionText = if (showActions && onScan != null) stringResource(R.string.chat_empty_action_scan) else null,
        onSecondaryAction = if (showActions) onScan else null
    )
}
