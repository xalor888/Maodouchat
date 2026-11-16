package com.maodouchat.domain.conversation

// 会话列表空态：加载失败空态只在无搜索、无归档、未选文件夹时显示；操作按钮的显隐条件与之相同、只是不要求加载失败。
object ChatListEmptyStatePolicy {

    fun isLoadErrorState(
        loadError: String?,
        hasSearchQuery: Boolean,
        showArchived: Boolean,
        selectedFolderId: String?,
    ): Boolean =
        loadError != null && !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()

    fun showEmptyActions(
        hasSearchQuery: Boolean,
        showArchived: Boolean,
        selectedFolderId: String?,
    ): Boolean = !hasSearchQuery && !showArchived && selectedFolderId.isNullOrBlank()
}
