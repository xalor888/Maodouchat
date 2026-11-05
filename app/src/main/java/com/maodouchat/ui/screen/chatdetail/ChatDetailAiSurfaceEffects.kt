package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.maodouchat.core.model.ChatSearchMode

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailAiSurfaceEffects(
    chatAiSurfacesVisible: Boolean,
    search: ChatDetailSearchState,
    viewModel: ChatDetailViewModel,
) {
    LaunchedEffect(chatAiSurfacesVisible) {
        if (!chatAiSurfacesVisible && search.searchMode == ChatSearchMode.SEMANTIC) {
            search.searchMode = ChatSearchMode.KEYWORD
            viewModel.clearSemanticSearch()
        }
    }
}
