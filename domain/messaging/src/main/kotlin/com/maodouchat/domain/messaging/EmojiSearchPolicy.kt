package com.maodouchat.domain.messaging

// 表情搜索：表情本身（区分大小写，表情字符无大小写之分）或任一常用别名含查询词即命中。
// 自 ExpressionEmojiTab 的 filteredEmojis 收敛。
object EmojiSearchPolicy {

    fun matches(emoji: String, aliases: Map<String, List<String>>, query: String): Boolean =
        emoji.contains(query) ||
            aliases[emoji].orEmpty().any { it.contains(query, ignoreCase = true) }

    fun filter(
        emojis: List<String>,
        aliases: Map<String, List<String>>,
        query: String
    ): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emojis
        return emojis.filter { matches(it, aliases, trimmed) }
    }
}
