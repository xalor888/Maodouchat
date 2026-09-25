package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * G335：**AI 面板与杂项素材弹层**的开合开关。
 *
 * 归族理由：会话画像 / 本周周报 / 消息分类三个 AI 面板，加上 AI 摘要范围、GIF 搜索、
 * 举报联系人三处素材/操作弹层——它们都是「从溢出菜单或 composer 打开一层覆盖 UI」，
 * 且**与结果数据无关**（结果三态已经在 [ChatDetailAiResultState] 里）。
 * 把「面板开没开」和「面板里显示什么」分开，是这批搬迁里最重要的一条边界：
 * 前者是 UI 位置（跨旋转该记住），后者是计算结果（不该跨进程存活）。
 *
 * 范式：原本逐个 `rememberSaveable` → 持有类自带 [Saver]，
 * 用 [rememberChatDetailAiPanelState] 创建，保存语义不变。
 */
internal class ChatDetailAiPanelState(
    showConversationProfile: Boolean = false,
    showWeeklyReport: Boolean = false,
    showMessageClassify: Boolean = false,
    showAiSummaryScopeDialog: Boolean = false,
    showGifSearch: Boolean = false,
    showReportContactDialog: Boolean = false,
) {
    var showConversationProfile by mutableStateOf(showConversationProfile)
    var showWeeklyReport by mutableStateOf(showWeeklyReport)
    var showMessageClassify by mutableStateOf(showMessageClassify)
    var showAiSummaryScopeDialog by mutableStateOf(showAiSummaryScopeDialog)
    var showGifSearch by mutableStateOf(showGifSearch)
    var showReportContactDialog by mutableStateOf(showReportContactDialog)

    /** 从溢出菜单进另一个 AI 面板时，先把同族的都收起（避免两层叠在一起）。 */
    fun closeAiPanels() {
        showConversationProfile = false
        showWeeklyReport = false
        showMessageClassify = false
        showAiSummaryScopeDialog = false
    }

    companion object {
        val Saver = listSaver<ChatDetailAiPanelState, Any>(
            save = {
                listOf(
                    it.showConversationProfile,
                    it.showWeeklyReport,
                    it.showMessageClassify,
                    it.showAiSummaryScopeDialog,
                    it.showGifSearch,
                    it.showReportContactDialog,
                )
            },
            restore = {
                ChatDetailAiPanelState(
                    showConversationProfile = it[0] as Boolean,
                    showWeeklyReport = it[1] as Boolean,
                    showMessageClassify = it[2] as Boolean,
                    showAiSummaryScopeDialog = it[3] as Boolean,
                    showGifSearch = it[4] as Boolean,
                    showReportContactDialog = it[5] as Boolean,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价。 */
@Composable
internal fun rememberChatDetailAiPanelState(): ChatDetailAiPanelState =
    rememberSaveable(saver = ChatDetailAiPanelState.Saver) { ChatDetailAiPanelState() }
