package com.maodouchat.domain.messaging

/** 附带 retry-after 的错误码解析；[persistedCode] 可写入 Room lastErrorCode。 */
data class AiClassifiedError(
    val code: String,
    val retryAfterSeconds: Long? = null,
    val persistedCode: String = code
)
