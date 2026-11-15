package com.maodouchat.domain.conversation

// 全局搜索的类型过滤：过滤值 -> 可索引消息类型；LINK 无独立消息类型，落在
// TEXT/MARKDOWN 上，返回后在内存按 URL 判定。键用 SearchFilterType.apiValue，
// 枚举本身带 R.string 留在 app 侧。
object GlobalSearchTypeFilter {

    fun messageTypesForFilter(apiValue: String): List<String>? = when (apiValue) {
        "ALL" -> null
        "TEXT" -> listOf("TEXT", "MARKDOWN")
        "IMAGE" -> listOf("IMAGE", "GIF", "STICKER")
        "FILE" -> listOf("FILE")
        "VOICE" -> listOf("VOICE")
        "VIDEO" -> listOf("VIDEO")
        "LINK" -> listOf("TEXT", "MARKDOWN")
        else -> null
    }

    private val LINK_PATTERN = Regex("(?i)\\b(www\\.|t\\.me/|chat\\.mdou\\.me/)")

    fun isLinkText(searchableText: String): Boolean =
        searchableText.contains("://") || searchableText.contains(LINK_PATTERN)
}
