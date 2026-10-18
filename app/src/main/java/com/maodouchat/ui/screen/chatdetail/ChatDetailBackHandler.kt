package com.maodouchat.ui.screen.chatdetail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

// Route 的返回键处理：溢出菜单 / 多选模式 / 搜索条按优先级关闭，纯搬移不改判断。
@Composable
internal fun ChatDetailBackHandler(
    dialogs: ChatDetailDialogState,
    listDerived: ChatDetailListDerived,
    search: ChatDetailSearchState,
    drafts: ChatDetailDraftState,
) {
    BackHandler(enabled = dialogs.showChatOverflow || listDerived.messageSelectionMode || search.showSearchBar) {
        when {
            dialogs.showChatOverflow -> dialogs.showChatOverflow = false
            listDerived.messageSelectionMode -> drafts.selectedMessageIds = emptySet()
            search.showSearchBar -> search.showSearchBar = false
        }
    }
}
