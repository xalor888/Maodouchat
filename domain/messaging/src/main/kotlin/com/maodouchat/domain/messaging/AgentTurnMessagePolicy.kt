package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AgentChatMessage

// 轮次引擎的工具结果文案，自 app 侧 AgentSessionEngine 收敛；
// 未知工具文案与宿主侧同一句，复用 AgentToolHostPolicy，不另存副本。
object AgentTurnMessagePolicy {

    const val TOOL_DENIED: String = "Error: tool denied"

    const val TOO_MANY_ROUNDS: String = "工具轮次过多，已停止"

    const val PRIVILEGED_HALLUCINATION_NOTE: String =
        "助手不能执行未审批的特权动作，请以本机实际结果为准。"

    fun unknownTool(name: String): String = AgentToolHostPolicy.unknownTool(name)

    // 轮次开始前丢掉历史末尾与刚发用户消息重复的那几条，避免同一句进两遍。
    fun dropDuplicateLastUser(history: List<AgentChatMessage>, lastUserText: String): List<AgentChatMessage> =
        history.dropLastWhile { it.role == "user" && it.content == lastUserText }
}
