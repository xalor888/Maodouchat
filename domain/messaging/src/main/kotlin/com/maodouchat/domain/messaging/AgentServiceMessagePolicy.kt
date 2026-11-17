package com.maodouchat.domain.messaging

// 服务层提示文案：MaodouAgentService 与 LocalAiGateway 的用户可见
// 提示与工具结果落盘截断，自 app 侧收敛。
object AgentServiceMessagePolicy {

    const val AI_NOT_ENABLED: String = "请先在设置里打开 AI 并授权本机处理"

    const val MISSING_PROVIDER: String =
        "请先在设置 → AI 与隐私中填写本机模型（OpenAI Completions / Responses 或 Anthropic）、Key 和上下文。聊天明文不会发到毛豆服务器。"

    const val MAX_STORED_TOOL_RESULT_CHARS: Int = 500

    fun rejectTool(name: String): String = "用户拒绝了 $name"

    fun toolResultStored(result: String): String =
        result.take(MAX_STORED_TOOL_RESULT_CHARS)
}
