package com.maodouchat.domain.messaging

// 输入框长度上限：与服务端 sendMessage 4000 校验对齐；80%/90% 计数警告阈值沿用原整数运算。
object ComposerLengthPolicy {

    const val MAX_LENGTH: Int = 4000

    fun clip(text: String): String = text.take(MAX_LENGTH)

    fun warningLevel(length: Int): ComposerLengthWarning =
        when {
            length >= MAX_LENGTH * 9 / 10 -> ComposerLengthWarning.NEAR_LIMIT
            length >= MAX_LENGTH * 8 / 10 -> ComposerLengthWarning.APPROACHING
            else -> ComposerLengthWarning.NONE
        }
}

enum class ComposerLengthWarning {
    NONE,
    APPROACHING,
    NEAR_LIMIT,
}
