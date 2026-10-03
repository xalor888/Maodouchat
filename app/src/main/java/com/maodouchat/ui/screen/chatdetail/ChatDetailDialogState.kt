package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal class ChatDetailDialogState {
    /** 清空本机聊天记录确认对话框是否可见。 */
    var showClearHistoryConfirm by mutableStateOf(false)

    /** 批量删除确认对话框是否可见。 */
    var showBatchDeleteConfirm by mutableStateOf(false)

    /** 标题栏溢出菜单是否展开。 */
    var showChatOverflow by mutableStateOf(false)
}

/** 与原来的逐项 `remember { … }` 等价：持有类内部是普通的 `mutableStateOf`。 */
@Composable
internal fun rememberChatDetailDialogState(): ChatDetailDialogState =
    remember { ChatDetailDialogState() }
