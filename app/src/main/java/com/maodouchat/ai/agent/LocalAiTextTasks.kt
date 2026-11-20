package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.domain.messaging.AiContextMessage
import com.maodouchat.domain.messaging.AgentTextTaskPolicy
import com.maodouchat.domain.messaging.AiSuggestionCleanupPolicy
import com.maodouchat.domain.messaging.AgentTextCompletion
import com.maodouchat.network.HttpClients

// 本机模型文本任务簇：改写、翻译、回复建议、摘要、群助手。函数体从 LocalAiGateway 逐字搬出。
internal object LocalAiTextTasks {
    suspend fun rewrite(
        context: Context,
        text: String,
        mode: String,
        targetLanguage: String?,
        onDelta: ((String) -> Unit)? = null
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val style = AgentTextCompletion.styleHintFrom(AiWritingStylePreferences.snapshot(context))
        val instruction = AgentTextTaskPolicy.rewriteInstruction(mode, targetLanguage, style)
        return AgentTextCompletion.completeText(provider, instruction, text, onDelta, httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong()))
    }

    suspend fun translate(context: Context, text: String, targetLanguage: String): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        return AgentTextCompletion.completeText(
            provider,
            AgentTextTaskPolicy.translateInstruction(targetLanguage),
            text,
            httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())
        )
    }

    suspend fun suggestReplies(
        context: Context,
        messages: List<AiContextMessage>,
        tone: String,
        count: Int,
        onDelta: ((String) -> Unit)? = null
    ): Result<List<String>> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val transcript = AgentTextTaskPolicy.transcript(messages)
        return AgentTextCompletion.completeText(
            provider,
            AgentTextTaskPolicy.suggestInstruction(tone, count),
            transcript,
            onDelta,
            httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())
        ).map { raw -> AiSuggestionCleanupPolicy.cleanSuggestions(raw, count) }
    }

    suspend fun summarize(
        context: Context,
        messages: List<AiContextMessage>,
        style: String
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val transcript = AgentTextTaskPolicy.transcript(messages)
        return AgentTextCompletion.completeText(
            provider,
            AgentTextTaskPolicy.summarizeInstruction(style),
            transcript,
            httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())
        )
    }

    suspend fun groupAssistant(
        context: Context,
        query: String,
        messages: List<AiContextMessage>,
        mode: String
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val transcript = AgentTextTaskPolicy.transcript(messages)
        return AgentTextCompletion.completeText(
            provider,
            AgentTextTaskPolicy.groupAssistantInstruction(mode, query),
            transcript,
            httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())
        )
    }
}
