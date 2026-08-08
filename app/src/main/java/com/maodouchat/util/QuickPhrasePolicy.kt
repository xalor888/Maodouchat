package com.maodouchat.util

/**
 * 输入栏快捷短语（常用语）策略：默认短语 + 增删改校验（纯函数）。
 */
object QuickPhrasePolicy {
    const val MAX_PHRASES = 40
    const val MAX_PHRASE_LENGTH = 80

    val DEFAULT_PHRASES: List<String> = listOf(
        "好的，收到 👍",
        "稍等，我看一下",
        "在忙，稍后回复你",
        "谢谢你！",
        "没问题",
        "哈哈哈 😄",
        "收到",
        "好的，马上来"
    )

    fun isAddable(existing: List<String>, phrase: String): Boolean {
        val value = phrase.trim()
        if (value.isEmpty() || value.length > MAX_PHRASE_LENGTH) return false
        if (existing.any { it == value }) return false
        return existing.size < MAX_PHRASES
    }

    fun add(existing: List<String>, phrase: String): List<String> =
        if (isAddable(existing, phrase)) existing + phrase.trim() else existing

    fun remove(existing: List<String>, phrase: String): List<String> =
        existing.filter { it != phrase }
}
