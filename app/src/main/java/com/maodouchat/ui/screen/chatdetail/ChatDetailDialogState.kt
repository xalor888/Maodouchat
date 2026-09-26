package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * G336（第十五批）：**会话级弹窗开关族**——Route 里三个 Boolean 型的顶层弹窗/菜单可见性开关。
 *
 * 归族理由：`showClearHistoryConfirm`（清空本机聊天记录确认）、`showBatchDeleteConfirm`
 * （批量删除确认）、`showChatOverflow`（标题栏溢出菜单）都是「入口置 true、
 * onDismiss 置 false」的纯瞬态开关，没有任何联动逻辑；收进持有类后 Route 侧只剩
 * `dialogs.*` 接线。
 *
 * 范式：普通持有类（`remember`，不带 Saver）。弹窗/菜单的可见性是纯瞬态视图状态：
 * 旋转/进程重建后对话框自然消失，没有「恢复上次开着哪个弹窗」的需求，故不进 saveable。
 * （`saveable` 族共 43 个仍在 Route 里——硬搬会静默丢失旋转恢复语义，要搬得先写 Saver，
 * 详见 checklist 第十一轮小节。）
 */
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
