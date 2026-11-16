package com.maodouchat.domain.messaging

import com.maodouchat.core.model.TranslationLanguageOption

// 翻译语言选项搜索：展示名或 wire 值任一含查询词即命中。
// 自 TranslationLanguageDialog 的 filteredLanguages 收敛。
object TranslationLanguageFilterPolicy {

    fun matches(option: TranslationLanguageOption, label: String, query: String): Boolean =
        label.contains(query, ignoreCase = true) ||
            option.wireValue.contains(query, ignoreCase = true)

    fun filter(
        options: List<Pair<TranslationLanguageOption, String>>,
        query: String
    ): List<Pair<TranslationLanguageOption, String>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return options
        return options.filter { (option, label) -> matches(option, label, trimmed) }
    }
}
