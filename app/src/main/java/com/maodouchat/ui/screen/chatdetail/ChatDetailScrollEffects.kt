package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import com.maodouchat.perf.CoalescedScroller

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailScrollEffects(
    state: ChatDetailUiState,
    scroll: ChatDetailScrollState,
    chatListScroller: CoalescedScroller,
    listState: LazyListState,
    targets: ChatDetailMessageTargetState,
    viewModel: ChatDetailViewModel,
    reversedChatItems: List<ChatItem>,
) {
    // G335（第十三批）：新消息到达时的自动滚动决策收进 scroll 持有类（见 ChatDetailScrollState.onLatestMessage）
    LaunchedEffect(
        state.messages.lastOrNull()?.id,
        state.currentUserId,
        state.initialTimelineReady,
        reversedChatItems.size
    ) {
        val latestMessage = state.messages.lastOrNull() ?: return@LaunchedEffect
        scroll.onLatestMessage(
            latestId = latestMessage.id,
            latestSenderId = latestMessage.senderId,
            currentUserId = state.currentUserId,
            initialTimelineReady = state.initialTimelineReady,
            navigationTargetMessageId = state.navigationTargetMessageId,
            navigationHighlightMessageId = targets.navigationHighlightMessageId,
            scrollToItem = { animated -> chatListScroller.scrollToItem(listState, 0, animated = animated) },
        )
    }

    LaunchedEffect(scroll.isNearBottom) {
        scroll.clearPendingOnNearBottom()
    }

    LaunchedEffect(scroll.shouldLoadOlderMessages) {
        if (scroll.shouldLoadOlderMessages) viewModel.loadOlderMessages()
    }
}
