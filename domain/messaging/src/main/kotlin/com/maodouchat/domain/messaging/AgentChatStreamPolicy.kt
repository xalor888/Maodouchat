package com.maodouchat.domain.messaging

// 模型聊天是否走流式：端点开流、调用方要增量、且无工具调用三者同时成立。
// 自 app 侧 OpenAiCompatChatClient.complete 收敛。
object AgentChatStreamPolicy {

    fun shouldStream(streamEnabled: Boolean, hasDelta: Boolean, tools: List<*>?): Boolean =
        streamEnabled && hasDelta && tools.isNullOrEmpty()
}
