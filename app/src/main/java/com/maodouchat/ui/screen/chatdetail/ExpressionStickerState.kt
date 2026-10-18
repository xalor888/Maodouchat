package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.util.EmojiRecentPreferences
import com.maodouchat.util.StickerCatalog
import com.maodouchat.util.StickerPack
import com.maodouchat.util.StickerPolicy
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
        get() = when {
            stickerQuery.value.isNotBlank() -> searchHits
            stickerTab.value == StickerCatalog.PACK_RECENT -> recentStickers.value
            else -> enabledPacks.firstOrNull { it.id == stickerTab.value }?.stickers
                ?: enabledPacks.firstOrNull()?.stickers
                ?: emptyList()
        }
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
