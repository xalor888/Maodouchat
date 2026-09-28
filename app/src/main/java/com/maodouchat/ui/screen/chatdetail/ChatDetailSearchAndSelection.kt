package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R
import com.maodouchat.data.model.Message

/**
 * G348：会话详情「搜索条 + 多选工具条」两块从 `ChatDetailRoute.kt` 抽出（纯搬移不改判断）。
 *
 * - [ChatDetailSearchSection]：搜索条（关键词/语义两态、作用域/窗口/结果导航、关闭重置）；
 * - [ChatDetailSelectionSection]：多选工具条（G84 抽出的 ChatDetailSelectionToolbar 的接线）。
 *
 * 依赖全经参数注入；组合期内不新增状态所有权，开关读写语义逐字一致。
 */
@Composable
internal fun ChatDetailSearchSection(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    search: ChatDetailSearchState,
    searchResults: List<Message>,
    semanticCandidates: List<Message>,
    chatAiSurfacesVisible: Boolean,
) {
    AnimatedVisibility(
        visible = search.showSearchBar,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        ChatSearchBar(
            query = search.searchQuery,
            mode = search.searchMode,
            scope = search.searchScope,
            window = search.searchWindow,
            resultIndex = search.searchIndex,
            resultCount = searchResults.size,
            semanticCandidateCount = semanticCandidates.size,
            isSemanticSearching = state.isSemanticSearching,
            semanticSearchQuery = state.semanticSearchQuery,
            semanticSearchResultCount = state.semanticSearchResultIds.size,
            semanticSearchError = state.semanticSearchError,
            aiEnabled = chatAiSurfacesVisible,
            onQueryChange = { query ->
                search.searchQuery = query
                search.searchIndex = 0
                if (search.searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
            },
            onModeChange = { mode ->
                search.searchMode = mode
                search.searchIndex = 0
                viewModel.clearSemanticSearch()
            },
            onScopeChange = { scope ->
                search.searchScope = scope
                search.searchIndex = 0
                if (search.searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
            },
            onWindowChange = { window ->
                search.searchWindow = window
                search.searchIndex = 0
                if (search.searchMode == ChatSearchMode.SEMANTIC) viewModel.clearSemanticSearch()
            },
            onSemanticSearch = {
                search.searchIndex = 0
                viewModel.requestSemanticSearch(search.searchQuery, semanticCandidates.map(Message::id))
            },
            onNextResult = { search.searchIndex = (search.searchIndex + 1) % searchResults.size },
            onClose = {
                search.showSearchBar = false
                search.searchQuery = ""
                search.searchIndex = 0
                search.searchMode = ChatSearchMode.KEYWORD
                search.searchScope = ChatSearchScope.ALL
                search.searchWindow = ChatSearchWindow.ALL
                viewModel.clearSemanticSearch()
            }
        )
    }
}

/**
 * 多选工具条的接线（G84 把工具条本体抽到 `ChatDetailSelectionToolbar.kt`，这里是它在本页的挂载点）。
 */
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailSelectionSection(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    drafts: ChatDetailDraftState,
    messageActions: ChatDetailMessageActionState,
    dialogs: ChatDetailDialogState,
    messageSelectionMode: Boolean,
    selectedMessages: List<Message>,
    chatClipboardMessageLabel: String,
    chatCopiedMsg: String,
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = messageSelectionMode,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        ChatDetailSelectionToolbar(
            visible = messageSelectionMode,
            selectedMessages = selectedMessages,
            allMessages = state.messages,
            selectedIds = drafts.selectedMessageIds,
            chatIsGroup = state.chatIsGroup,
            myMemberRole = state.myMemberRole,
            pinnedMessageIds = remember(state.pinnedMessages) { state.pinnedMessages.map { it.messageId }.toSet() },
            isSecretChat = state.isSecretChat == true,
            preparingAttachmentMessageIds = state.preparingAttachmentMessageIds,
            onSelectAll = { drafts.selectedMessageIds = it },
            onClearSelection = { drafts.selectedMessageIds = emptySet() },
            onForward = { msgs ->
                messageActions.messagesToForward = msgs
                viewModel.loadForwardTargets()
            },
            onToggleStar = { ids, shouldStar -> viewModel.toggleStarMessagesBatch(ids, shouldStar) },
            onDelete = { dialogs.showBatchDeleteConfirm = true },
            onTogglePin = { ids, shouldPin ->
                viewModel.togglePinMessages(messageIds = ids, shouldPin = shouldPin)
            },
            onCopied = { text ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, text))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onCopyFailed = {
                Toast.makeText(context, context.getString(R.string.chat_copy_no_text), Toast.LENGTH_SHORT).show()
            },
        )
    }
}
