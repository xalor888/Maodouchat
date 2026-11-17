package com.maodouchat.domain.messaging

// 模型调用 HTTP 错误文案，自 app 侧 ai/agent 三个 OpenAiCompat 客户端收敛；
// 网络请求与响应读取仍留在 app 侧。
object AgentModelErrorPolicy {

    fun formatHttpError(prefix: String, code: Int, payload: String, fallbackMessage: String): String =
        "$prefix $code: ${payload.take(240).ifBlank { fallbackMessage }}"
}
