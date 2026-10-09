package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.network.AiContextMessage

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
        val instruction = buildString {
            append(agentRewriteInstruction(mode, targetLanguage))
            if (!style.isNullOrBlank()) append(" ").append(style)
        }
        return AgentTextCompletion.completeText(provider, instruction, text, onDelta)
    }

    suspend fun translate(context: Context, text: String, targetLanguage: String): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        return AgentTextCompletion.completeText(
            provider,
            agentTranslateInstruction(targetLanguage),
            text
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
        val transcript = messages.joinToString("\n") { "${it.sender}: ${it.text}" }
        return AgentTextCompletion.completeText(
            provider,
            agentSuggestInstruction(tone, count),
            transcript,
            onDelta
        ).map { raw ->
            raw.lineSequence()
                .map { it.trim().trimStart('-', '*', '•', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '.', ')', ' ') }
                .filter { it.isNotBlank() }
                .take(count.coerceIn(1, 4))
                .toList()
        }
    }

    suspend fun summarize(
        context: Context,
        messages: List<AiContextMessage>,
        style: String
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val transcript = messages.joinToString("\n") { "${it.sender}: ${it.text}" }
        return AgentTextCompletion.completeText(
            provider,
            agentSummarizeInstruction(style),
            transcript
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
        val transcript = messages.joinToString("\n") { "${it.sender}: ${it.text}" }
        return AgentTextCompletion.completeText(
            provider,
            agentGroupAssistantInstruction(mode, query),
            transcript
        )
    }
}
