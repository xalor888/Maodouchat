package com.maodouchat.domain.messaging

import com.maodouchat.core.model.StickerPack

// 表情面板贴纸页签展示项选择；状态持有仍在 app 侧。
object StickerTabDisplayPolicy {
    fun resolveItems(
        query: String,
        tabId: String,
        recent: List<String>,
        searchHits: List<String>,
        enabledPacks: List<StickerPack>,
    ): List<String> = when {
        query.isNotBlank() -> searchHits
        tabId == StickerCatalog.PACK_RECENT -> recent
        else -> enabledPacks.firstOrNull { it.id == tabId }?.stickers
            ?: enabledPacks.firstOrNull()?.stickers
            ?: emptyList()
    }
}
