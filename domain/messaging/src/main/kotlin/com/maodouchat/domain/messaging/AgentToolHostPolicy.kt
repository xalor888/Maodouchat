package com.maodouchat.domain.messaging


// Agent 工具宿主的错误文案与结果处理，自 app 侧 AgentToolHost 收敛。
object AgentToolHostPolicy {

    const val NOT_SIGNED_IN: String = "Error: not signed in"

    const val REWRITE_TOOL_NAME: String = "rewrite_text"

    const val REWRITE_HANDLED_BY_ENGINE: String =
        "Error: $REWRITE_TOOL_NAME is handled by the engine, not the host"

    // 由 Agent 创建的记录的来源标记：发送消息的 aiAssistantMode 与任务的 sourceQuery 共用。
    const val AGENT_SOURCE: String = "agent"

    const val PREVIEW_ARGS_CHARS: Int = 160

    fun unknownTool(name: String): String = "Error: unknown tool $name"

    fun previewFallback(name: String, argumentsJson: String): String =
        "$name ${argumentsJson.take(PREVIEW_ARGS_CHARS)}"

    fun truncateResult(text: String): String =
        text.take(AgentToolPolicy.MAX_TOOL_RESULT_CHARS)
}
