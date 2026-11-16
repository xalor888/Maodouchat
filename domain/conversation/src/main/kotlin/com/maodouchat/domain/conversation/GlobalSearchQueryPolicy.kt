package com.maodouchat.domain.conversation

// 全局搜索查询上限与 AI 候选上限：查询输入截断、最近搜索归一化、AI 本地候选与命中上限。
object GlobalSearchQueryPolicy {

    const val MAX_QUERY_LENGTH: Int = 400
    const val RECENT_QUERY_MAX_LENGTH: Int = 100
    const val AI_CANDIDATE_LIMIT: Int = 80
    const val AI_CHAT_LIMIT: Int = 32
    const val AI_HIT_LIMIT: Int = 32

    fun filterQuery(value: String): String = value.take(MAX_QUERY_LENGTH)

    fun normalizeRecentQuery(query: String): String =
        query.trim().take(RECENT_QUERY_MAX_LENGTH)

    fun limitChatIds(chatIds: List<String>): List<String> =
        chatIds.distinct().take(AI_CHAT_LIMIT)

    fun <T> limitDocuments(documents: List<T>): List<T> =
        documents.take(AI_CANDIDATE_LIMIT)

    fun <T> limitHits(hits: List<T>): List<T> = hits.take(AI_HIT_LIMIT)
}
