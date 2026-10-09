package com.maodouchat.ai.agent

import android.content.Context
import java.util.UUID

// provider 草稿簇：新建端点时的协议默认草稿 + 可用性判定。
// 原 LocalAiProviderStorage 的草稿部分整体搬入。
internal object LocalAiProviderDrafts {
    fun newProviderDraft(protocol: LocalAiProtocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS): LocalAiProvider {
        val (name, base, model) = when (protocol) {
            LocalAiProtocol.OPENAI_CHAT_COMPLETIONS ->
                Triple("OpenAI Chat Completions", "https://api.openai.com/v1", "gpt-4o-mini")
            LocalAiProtocol.OPENAI_RESPONSES ->
                Triple("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini")
            LocalAiProtocol.ANTHROPIC_MESSAGES ->
                Triple("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5")
        }
        return LocalAiProvider(
            id = "p_${UUID.randomUUID()}",
            name = name,
            baseUrl = base,
            apiKey = "",
            model = model,
            protocol = protocol
        )
    }

    fun isConfigured(context: Context): Boolean {
        val p = LocalAiProviderCrud.activeProvider(context) ?: return false
        return p.baseUrl.isNotBlank() && p.model.isNotBlank() && p.apiKey.isNotBlank()
    }
}
