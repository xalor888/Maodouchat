package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocalAiProtocol

// 新建端点草稿的协议默认（名称/地址/模型），自 app 侧 ai/agent 草稿簇收敛；
// id 生成仍留在 app 侧，只迁协议到默认值的映射本身。
object LocalAiProviderDraftPolicy {

    fun draftDefaults(protocol: LocalAiProtocol): Triple<String, String, String> = when (protocol) {
        LocalAiProtocol.OPENAI_CHAT_COMPLETIONS ->
            Triple("OpenAI Chat Completions", "https://api.openai.com/v1", "gpt-4o-mini")
        LocalAiProtocol.OPENAI_RESPONSES ->
            Triple("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini")
        LocalAiProtocol.ANTHROPIC_MESSAGES ->
            Triple("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5")
    }
}
