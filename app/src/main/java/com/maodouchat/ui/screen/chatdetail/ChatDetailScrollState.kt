package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal class ChatDetailScrollState(listState: LazyListState) {
    /** 是否在底部附近（第一条可见项 index ≤ 1）：控制自动贴底与悬浮按钮显隐。 */
    val isNearBottom: Boolean by derivedStateOf { listState.firstVisibleItemIndex <= 1 }

    /** 不在底部时收到的新消息计数；回到附近或点悬浮按钮后清零。 */
    var pendingNewMessageCount by mutableIntStateOf(0)

    /** 自动滚动决策上一次见到的最新消息 id；开场 pin 阶段的识别依据。 */
    var lastAutoScrollMessageId by mutableStateOf<String?>(null)

    /** 滚动到顶部附近（距最老项 6 条内）时为 true，Route 侧触发 `loadOlderMessages()`。 */
    val shouldLoadOlderMessages: Boolean by derivedStateOf {
        val layout = listState.layoutInfo
        val oldestVisibleIndex = layout.visibleItemsInfo.maxOfOrNull { it.index } ?: return@derivedStateOf false
        layout.totalItemsCount > 0 && oldestVisibleIndex >= layout.totalItemsCount - 6
    }

    /**
     * 新消息到达时的自动滚动决策（原 Route 的 `LaunchedEffect(latestId…)` 逐字搬移）。
     *
     * @param scrollToItem 由 Route 注入的合并滚动器调用
     *   （`chatListScroller.scrollToItem(listState, 0, animated = …)`）。
     */
    fun onLatestMessage(
        latestId: String,
        latestSenderId: String,
        currentUserId: String,
        initialTimelineReady: Boolean,
        navigationTargetMessageId: String?,
        navigationHighlightMessageId: String?,
        scrollToItem: (animated: Boolean) -> Unit,
    ) {
        if (navigationTargetMessageId != null || navigationHighlightMessageId != null) {
            lastAutoScrollMessageId = latestId
            return
        }
        val previousId = lastAutoScrollMessageId
        lastAutoScrollMessageId = latestId
        // Open-chat: local seed paints an older tail first; history then prepends newer
        // bubbles in reverseLayout. Keep index 0 until that merge finishes, otherwise
        // the viewport stays on yesterday while list preview already shows today.
        val openingPin = !initialTimelineReady || previousId == null
        if (previousId == latestId && !openingPin) return
        val shouldStickToBottom = openingPin || isNearBottom || latestSenderId == currentUserId
        if (shouldStickToBottom) {
            scrollToItem(!openingPin)
            pendingNewMessageCount = 0
        } else if (latestSenderId != currentUserId && previousId != latestId) {
            pendingNewMessageCount += 1
        }
    }

    /** 回到底部附近：徽标清零（原 `LaunchedEffect(isNearBottom)` 的逐字搬移）。 */
    fun clearPendingOnNearBottom() {
        if (isNearBottom) pendingNewMessageCount = 0
    }
}

/** 与原来的逐项 `remember { … }` 等价：`listState` 是 Route 的稳定列表状态。 */
@Composable
internal fun rememberChatDetailScrollState(listState: LazyListState): ChatDetailScrollState =
    remember { ChatDetailScrollState(listState) }
