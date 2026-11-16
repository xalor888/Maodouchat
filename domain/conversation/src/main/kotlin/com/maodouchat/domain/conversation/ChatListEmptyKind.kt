package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatFolderPolicy

// 会话列表空态种类；标题/副标题的 R.string 映射仍在 app 侧。
enum class ChatListEmptyKind {
    SEARCH,
    ARCHIVED,
    UNREAD,
    GROUPS,
    DIRECT,
    SECRET,
    LOCKED,
    CUSTOM_FOLDER,
    DEFAULT,
}

fun chatListEmptyKind(
    hasSearchQuery: Boolean,
    showArchived: Boolean,
    selectedFolderId: String?,
): ChatListEmptyKind =
    when {
        hasSearchQuery -> ChatListEmptyKind.SEARCH
        showArchived -> ChatListEmptyKind.ARCHIVED
        selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID -> ChatListEmptyKind.UNREAD
        selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID -> ChatListEmptyKind.GROUPS
        selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID -> ChatListEmptyKind.DIRECT
        selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID -> ChatListEmptyKind.SECRET
        selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID -> ChatListEmptyKind.LOCKED
        !selectedFolderId.isNullOrBlank() -> ChatListEmptyKind.CUSTOM_FOLDER
        else -> ChatListEmptyKind.DEFAULT
    }
