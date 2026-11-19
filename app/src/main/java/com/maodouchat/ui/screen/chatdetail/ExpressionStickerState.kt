package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.domain.messaging.StickerPack
import com.maodouchat.domain.messaging.StickerCatalog
import com.maodouchat.domain.messaging.StickerPolicy
import com.maodouchat.domain.messaging.StickerTabDisplayPolicy
import com.maodouchat.util.EmojiRecentPreferences
import com.maodouchat.util.StickerPreferences

// 表情面板贴纸页签的状态簇：remember/rememberSaveable 的键与原内联一一对应，displayItems 保持原 when 判定。
// 调用方用 `var x by stickerState.x` 本地委托接线（ComposerState 同款），下游读写零改动。
internal class ExpressionStickerState(
    val stickerTab: MutableState<String>,
    val stickerQuery: MutableState<String>,
    val showPackManager: MutableState<Boolean>,
    val enabledPackIds: MutableState<List<String>>,
    val recentStickers: MutableState<List<String>>,
    val recentEmojis: MutableState<List<String>>,
    val enabledPacks: List<StickerPack>,
    val searchHits: List<String>,
) {
    val displayItems: List<String>
        get() = StickerTabDisplayPolicy.resolveItems(
            query = stickerQuery.value,
            tabId = stickerTab.value,
            recent = recentStickers.value,
            searchHits = searchHits,
            enabledPacks = enabledPacks,
        )
}

@Composable
internal fun rememberExpressionStickerState(): ExpressionStickerState {
    val context = LocalContext.current
    val stickerTab = rememberSaveable { mutableStateOf(StickerCatalog.PACK_RECENT) }
    val stickerQuery = rememberSaveable { mutableStateOf("") }
    val showPackManager = rememberSaveable { mutableStateOf(false) }
    val enabledPackIds = remember { mutableStateOf(StickerPreferences.getEnabledPackIds(context)) }
    val recentStickers = remember { mutableStateOf(StickerPreferences.getRecent(context)) }
    val recentEmojis = remember { mutableStateOf(EmojiRecentPreferences.getRecent(context)) }
    val enabledPacks = remember(enabledPackIds.value) { StickerPolicy.enabledPacks(enabledPackIds.value) }
    val searchHits = remember(stickerQuery.value, enabledPackIds.value) {
        StickerPolicy.searchStickers(stickerQuery.value, enabledPacks)
    }
    return ExpressionStickerState(
        stickerTab = stickerTab,
        stickerQuery = stickerQuery,
        showPackManager = showPackManager,
        enabledPackIds = enabledPackIds,
        recentStickers = recentStickers,
        recentEmojis = recentEmojis,
        enabledPacks = enabledPacks,
        searchHits = searchHits,
    )
}
