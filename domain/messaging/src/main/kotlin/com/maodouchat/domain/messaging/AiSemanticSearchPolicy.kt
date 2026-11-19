package com.maodouchat.domain.messaging

// 语义搜索候选构建与结果上限：发送者名/候选文本截断、候选上限、排序后白名单过滤去重取前 12。
object AiSemanticSearchPolicy {

    const val SENDER_MAX_LENGTH: Int = 80
    const val TEXT_MAX_LENGTH: Int = 700
    const val CANDIDATE_LIMIT: Int = 80
    const val RESULT_LIMIT: Int = 12

    fun candidate(
        messageId: String,
        sender: String,
        text: String,
        timestamp: Long,
    ): AiSemanticSearchCandidate = AiSemanticSearchCandidate(
        messageId = messageId,
        sender = sender.take(SENDER_MAX_LENGTH),
        text = text.take(TEXT_MAX_LENGTH),
        timestamp = timestamp,
    )

    fun limitCandidates(
        candidates: List<AiSemanticSearchCandidate>,
    ): List<AiSemanticSearchCandidate> = candidates.take(CANDIDATE_LIMIT)

    fun selectResultIds(ranked: List<String>, allowedIds: Set<String>): List<String> =
        ranked.filter { it in allowedIds }.distinct().take(RESULT_LIMIT)
}
