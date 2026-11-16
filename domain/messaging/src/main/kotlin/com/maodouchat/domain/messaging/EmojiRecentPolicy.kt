package com.maodouchat.domain.messaging

// 表情「最近使用」：新表情顶到首位、去重、截断（与贴纸 StickerPolicy.pushRecent 同模式）。
object EmojiRecentPolicy {
    const val MAX_RECENT = 36

    fun pushRecent(existing: List<String>, emoji: String, max: Int = MAX_RECENT): List<String> {
        val value = emoji.trim()
        if (value.isEmpty()) return existing
        return (listOf(value) + existing.filter { it != value }).take(max.coerceAtLeast(1))
    }
}
