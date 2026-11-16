package com.maodouchat.domain.messaging

// AI 离线建议文本预处理上限：在线建议截断/过滤/取前 4，离线种子与近 3 条上下文截断。
object AiOfflineSuggestionPolicy {

    const val MAX_SUGGESTIONS: Int = 4
    const val REPLY_MAX_LENGTH: Int = 500
    const val SEED_MAX_LENGTH: Int = 120
    const val CONTEXT_MESSAGE_COUNT: Int = 3
    const val CONTEXT_MAX_LENGTH: Int = 240

    fun prepareReplies(replies: List<String>): List<String> =
        replies.map { it.take(REPLY_MAX_LENGTH) }
            .filter { it.isNotBlank() }
            .take(MAX_SUGGESTIONS)

    fun buildSeed(lastText: String): String = lastText.take(SEED_MAX_LENGTH)

    fun buildRecent(texts: List<String>): String =
        texts.takeLast(CONTEXT_MESSAGE_COUNT).joinToString(" ").take(CONTEXT_MAX_LENGTH)
}
