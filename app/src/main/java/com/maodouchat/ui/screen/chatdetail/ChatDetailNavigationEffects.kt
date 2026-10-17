package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.widget.Toast

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.perf.CoalescedScroller

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailNavigationEffects(
    state: ChatDetailUiState,
    search: ChatDetailSearchState,
    searchResults: List<Message>,
    reversedChatItems: List<ChatItem>,
    targets: ChatDetailMessageTargetState,
    viewModel: ChatDetailViewModel,
    chatListScroller: CoalescedScroller,
    listState: LazyListState,
    context: Context,
) {
    LaunchedEffect(searchResults, search.searchIndex) {
        val target = searchResults.getOrNull(search.searchIndex) ?: return@LaunchedEffect
        val targetIndex = reversedChatItems.indexOfFirst { it is ChatItem.Msg && it.message.id == target.id }
        if (targetIndex >= 0) {
            chatListScroller.scrollToItem(listState, targetIndex)
            // 1.343：搜索当前结果消息闪烁高亮（复用导航高亮机制，便于定位）
            targets.navigationHighlightMessageId = target.id
            try {
                kotlinx.coroutines.delay(1_800)
            } finally {
                if (targets.navigationHighlightMessageId == target.id) targets.navigationHighlightMessageId = null
            }
        }
    }

    LaunchedEffect(state.navigationTargetMessageId, reversedChatItems.size) {
        val targetId = state.navigationTargetMessageId ?: return@LaunchedEffect
        val targetIndex = reversedChatItems.indexOfFirst { it is ChatItem.Msg && it.message.id == targetId }
        if (targetIndex < 0) {
            // 目标消息尚未加载（引用/置顶跳转可能指向已滚出当前窗口的旧消息）：
            // 还有更早历史则继续翻页加载，翻完仍不存在（消息被删/目标无效）则放弃并提示。
            if (state.hasMoreOlderMessages) {
                viewModel.loadOlderMessages()
            } else {
                viewModel.consumeNavigationTarget()
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_reply_target_not_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return@LaunchedEffect
        }
       chatListScroller.scrollToItem(listState, targetIndex)
       targets.navigationHighlightMessageId = targetId
        try {
            kotlinx.coroutines.delay(1_800)
        } finally {
            if (targets.navigationHighlightMessageId == targetId) targets.navigationHighlightMessageId = null
        }
        viewModel.consumeNavigationTarget()
    }

    LaunchedEffect(search.searchMode, search.searchScope, search.searchWindow) {
        search.searchIndex = 0
    }

    LaunchedEffect(searchResults.size) {
        if (searchResults.isEmpty()) search.searchIndex = 0
        else if (search.searchIndex >= searchResults.size) search.searchIndex = searchResults.lastIndex
    }
}
