package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.ShimmerChatRow
import com.maodouchat.util.ChatFolderPolicy
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
    if (loadError != null && !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()) {
        EmptyState(
            type = EmptyStateType.NETWORK_ERROR,
            title = stringResource(R.string.chat_load_failed_title),
            subtitle = loadError,
            actionText = stringResource(R.string.chat_load_failed_retry),
            onAction = onRetry
        )
        return
    }
    val title = when {
        hasSearchQuery -> stringResource(R.string.chat_empty_search_title)
        showArchived -> stringResource(R.string.chat_empty_archived_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> stringResource(R.string.chat_folder_empty_unread_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> stringResource(R.string.chat_folder_empty_groups_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> stringResource(R.string.chat_folder_empty_direct_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> stringResource(R.string.chat_folder_empty_secret_title)
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> stringResource(R.string.chat_folder_empty_locked_title)
        !selectedFolderId.isNullOrBlank() -> stringResource(R.string.chat_folder_empty)
        else -> stringResource(R.string.chat_empty_title)
    }
    val subtitle = when {
        hasSearchQuery -> stringResource(R.string.chat_empty_search_subtitle)
        showArchived -> stringResource(R.string.chat_empty_archived_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> stringResource(R.string.chat_folder_empty_unread_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> stringResource(R.string.chat_folder_empty_groups_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> stringResource(R.string.chat_folder_empty_direct_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> stringResource(R.string.chat_folder_empty_secret_subtitle)
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> stringResource(R.string.chat_folder_empty_locked_subtitle)
        !selectedFolderId.isNullOrBlank() -> stringResource(R.string.chat_folder_empty_subtitle)
        else -> stringResource(R.string.chat_empty_subtitle)
    }
    val showActions = !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()
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
