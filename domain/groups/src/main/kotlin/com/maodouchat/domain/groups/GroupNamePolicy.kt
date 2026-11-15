package com.maodouchat.domain.groups

// 群名纯规则：输入框过滤与保存前校验，不依赖 Android。
// 自建群输入框、群资料编辑页与改名控制器三处逐字相同的 50 字符上限收敛；
// 与服务端 /api/chats/{chatId}/name 的 1-50 字符校验一致，错误文案仍在调用方。
object GroupNamePolicy {

    const val MAX_LENGTH: Int = 50

    sealed interface Validation {
        data object Valid : Validation
        data object Blank : Validation
        data object TooLong : Validation
    }

    // 输入过滤：截 50 字符，不做 trim（与原输入框行为一致，空格由保存时处理）。
    fun filterInput(raw: String): String = raw.take(MAX_LENGTH)

    // 归一化：去首尾空格后截 50 字符，与原 Agent 工具的 trim().take(50) 一致。
    fun normalize(raw: String): String = raw.trim().take(MAX_LENGTH)

    // 保存前校验：先 trim 再判空与长度，与原改名控制器的顺序一致。
    fun validate(raw: String): Validation {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> Validation.Blank
            trimmed.length > MAX_LENGTH -> Validation.TooLong
            else -> Validation.Valid
        }
    }
}
