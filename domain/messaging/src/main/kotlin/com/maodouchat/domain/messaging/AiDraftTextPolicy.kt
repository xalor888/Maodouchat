package com.maodouchat.domain.messaging

// AI 改写/翻译生成文本上限：数值与服务端 sendMessage 4000 校验一致，但这是生成文本侧的独立上限，不复用输入框的 ComposerLengthPolicy。
object AiDraftTextPolicy {

    const val MAX_CHARS: Int = 4000

    fun truncate(text: String): String = text.take(MAX_CHARS)

    fun isFull(used: Int): Boolean = used >= MAX_CHARS

    fun appendBudget(used: Int): Int = (MAX_CHARS - used).coerceAtLeast(0)
}
