package com.maodouchat.domain.messaging

// 语言模式原始字符串 → 种类。本地路径只认 zh/en；远程路径多认 zh-cn/chinese/english
// 别名（既有行为差异，allowAliases 原样保留，不得把别名扩到本地路径）。
enum class LanguageModeKind {
    SYSTEM, CHINESE, ENGLISH
}

fun languageModeKind(raw: String, allowAliases: Boolean = false): LanguageModeKind {
    return when (raw.lowercase()) {
        "zh" -> LanguageModeKind.CHINESE
        "en" -> LanguageModeKind.ENGLISH
        "zh-cn", "chinese" -> if (allowAliases) LanguageModeKind.CHINESE else LanguageModeKind.SYSTEM
        "english" -> if (allowAliases) LanguageModeKind.ENGLISH else LanguageModeKind.SYSTEM
        else -> LanguageModeKind.SYSTEM
    }
}
