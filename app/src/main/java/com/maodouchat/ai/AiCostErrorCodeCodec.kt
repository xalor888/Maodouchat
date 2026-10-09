package com.maodouchat.ai

// 错误码编解码簇：持久化错误码 `基码:秒数` 后缀的解析、比较与编码。
internal object AiCostErrorCodeCodec {

    /**
     * 持久化错误码可带 `RATE_LIMITED:45` 后缀秒数；比较 / 策略只看基码。
     */
    fun baseErrorCode(errorCode: String?): String {
        val raw = (errorCode ?: "").trim()
        if (raw.isEmpty()) return ""
        val head = raw.substringBefore(':').trim().uppercase()
        return head.ifBlank { raw.uppercase() }
    }

    fun embeddedRetryAfterSeconds(errorCode: String?): Long? {
        val raw = (errorCode ?: "").trim()
        val sep = raw.indexOf(':')
        if (sep <= 0 || sep >= raw.lastIndex) return null
        return raw.substring(sep + 1).trim().toLongOrNull()?.takeIf { it > 0L }?.coerceAtMost(86_400L)
    }

    fun encodeErrorCode(base: String, retryAfterSeconds: Long? = null): String {
        val clean = baseErrorCode(base).ifBlank { base.trim().uppercase() }
        val seconds = retryAfterSeconds?.takeIf { it > 0L }?.coerceAtMost(86_400L)
        return if (seconds != null &&
            (clean == AiCostVisibilityPolicy.ERROR_RATE_LIMIT ||
                clean == AiCostVisibilityPolicy.ERROR_QUOTA ||
                clean.startsWith("RATE_LIMIT"))
        ) {
            "$clean:$seconds"
        } else {
            clean
        }
    }
}
