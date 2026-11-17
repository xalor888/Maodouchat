package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.core.model.LocalAiProtocol
import com.maodouchat.core.model.LocalAiProvider
import com.maodouchat.domain.messaging.AgentProviderConfiguredPolicy
import com.maodouchat.domain.messaging.LocalAiProviderDraftPolicy
import java.util.UUID

// provider 草稿簇：新建端点时的协议默认草稿 + 可用性判定。
// 原 LocalAiProviderStorage 的草稿部分整体搬入。
internal object LocalAiProviderDrafts {
    fun newProviderDraft(protocol: LocalAiProtocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS): LocalAiProvider {
        val (name, base, model) = LocalAiProviderDraftPolicy.draftDefaults(protocol)
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
        return AgentProviderConfiguredPolicy.isConfigured(p.baseUrl, p.model, p.apiKey)
    }
}
