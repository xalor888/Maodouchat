package com.maodouchat.server.service

/**
 * AI token 估算与重试常量（0.86：原 streaming client 从未被实例化已删除；
 * XAL-36：再清掉未用的 HttpClient / SSE DTO，避免与网关流式路径双份实现）。
 * 真实 AI 调用与流式取消由 [AiGatewayService] 负责。
 */
object AiStreamingService {
    const val CHUNK_TIMEOUT_MS = 30_000L
    const val MAX_RETRIES = 2
    const val INITIAL_BACKOFF_MS = 1_000L
    val RETRYABLE_STATUSES = setOf(500, 502, 503)

    fun estimateTokens(text: String): Int {
        var cjkCount = 0
        var otherCount = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF ||
                ch.code in 0x20000..0x2A6DF || ch.code in 0x2A700..0x2B73F ||
                ch.code in 0x2B740..0x2B81F || ch.code in 0x2B820..0x2CEAF ||
                ch.code in 0xF900..0xFAFF || ch.code in 0x2F800..0x2FA1F ||
                ch.code in 0x3040..0x309F || ch.code in 0x30A0..0x30FF ||
                ch.code in 0xAC00..0xD7AF
            ) {
                cjkCount++
            } else {
                otherCount++
            }
        }
        return (cjkCount / 2) + (otherCount / 4) + 1
    }
}
