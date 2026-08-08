package com.maodouchat.util

/**
 * 贴纸包选择 / 最近使用 / 搜索（纯函数，可单测）。
 */
object StickerPolicy {
    const val MAX_RECENT = 48

    /** 发送后把贴纸顶到最近列表；去重；截断。 */
    fun pushRecent(existing: List<String>, sticker: String, max: Int = MAX_RECENT): List<String> {
        val value = sticker.trim()
        if (value.isEmpty()) return existing
        return (listOf(value) + existing.filter { it != value }).take(max.coerceAtLeast(1))
    }

    fun normalizeEnabledPackIds(
        requested: List<String>?,
        availableIds: List<String> = StickerCatalog.defaultEnabledPackIds()
    ): List<String> {
        val available = availableIds.toSet()
        val filtered = (requested ?: emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in available }
            .distinct()
        return filtered.ifEmpty { availableIds }
    }

    fun enabledPacks(
        enabledIds: List<String>,
        catalog: List<StickerPack> = StickerCatalog.BUILT_IN_PACKS
    ): List<StickerPack> {
        val order = normalizeEnabledPackIds(enabledIds, catalog.map { it.id })
        val byId = catalog.associateBy { it.id }
        return order.mapNotNull { byId[it] }
    }

    /**
     * 关键词搜索：匹配贴纸本身、包 emojiTags key、或 tag 列表。
     * 空查询返回 empty（由 UI 展示整包）。
     */
    fun searchStickers(
        query: String,
        packs: List<StickerPack> = StickerCatalog.BUILT_IN_PACKS
    ): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = linkedSetOf<String>()
        packs.forEach { pack ->
            pack.stickers.forEach { sticker ->
                if (sticker.contains(q) || sticker.lowercase().contains(q)) hits += sticker
            }
            pack.emojiTags.forEach { (tag, stickers) ->
                if (tag.contains(q) || q in tag) {
                    hits += stickers
                } else {
                    stickers.forEach { s ->
                        if (s.contains(q)) hits += s
                    }
                }
            }
        }
        return hits.toList()
    }

    fun togglePackEnabled(
        enabledIds: List<String>,
        packId: String,
        enable: Boolean,
        availableIds: List<String> = StickerCatalog.defaultEnabledPackIds()
    ): List<String> {
        if (packId !in availableIds) return normalizeEnabledPackIds(enabledIds, availableIds)
        val current = normalizeEnabledPackIds(enabledIds, availableIds).toMutableList()
        if (enable) {
            if (packId !in current) current += packId
        } else {
            // 至少保留一个包
            if (current.size > 1) current.remove(packId)
        }
        return normalizeEnabledPackIds(current, availableIds)
    }
}
