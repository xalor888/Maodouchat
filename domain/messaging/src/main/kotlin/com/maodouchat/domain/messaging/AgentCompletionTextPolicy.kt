package com.maodouchat.domain.messaging

// 本机模型单轮文本调用的输入上限与结果规整；网络调用仍在 app 侧。
object AgentCompletionTextPolicy {
    const val MAX_USER_CONTENT_CHARS = 8_000

    fun capUserContent(content: String): String = content.take(MAX_USER_CONTENT_CHARS)

    fun trimResult(content: String): String = content.trim()
}
