package com.maodouchat.util

/**
 * 本机 GIF 搜索/排序策略（纯函数）。
 * 不依赖第三方图源；候选来自 MediaStore / 文档选择。
 */
data class LocalGifItem(
    val id: String,
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
)

object GifSearchPolicy {
    const val MAX_RECENT = 48
    const val MAX_RESULTS = 160

    fun normalizeQuery(raw: String?): String =
        raw.orEmpty().trim().lowercase().take(80)

    fun matches(item: LocalGifItem, query: String): Boolean {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return true
        val name = item.displayName.lowercase()
        return name.contains(q) ||
            name.replace('_', ' ').contains(q) ||
            name.replace('-', ' ').contains(q)
    }

    fun filterAndSort(
        items: List<LocalGifItem>,
        query: String,
        recentIds: List<String> = emptyList(),
        limit: Int = MAX_RESULTS
    ): List<LocalGifItem> {
        val q = normalizeQuery(query)
        val recentIndex = recentIds.withIndex().associate { it.value to it.index }
        return items
            .asSequence()
            .filter { matches(it, q) }
            .sortedWith(
                compareBy<LocalGifItem> { recentIndex[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending { it.dateAddedSec }
                    .thenBy { it.displayName.lowercase() }
            )
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun pushRecent(existing: List<String>, gifId: String, max: Int = MAX_RECENT): List<String> {
        val id = gifId.trim()
        if (id.isEmpty()) return existing
        return (listOf(id) + existing.filter { it != id }).take(max.coerceAtLeast(1))
    }
}
