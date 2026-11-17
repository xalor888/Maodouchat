package com.maodouchat.ai.agent

import com.maodouchat.core.model.AgentChatMessage
import com.maodouchat.core.model.AiWritingStylePolicy
import com.maodouchat.core.model.LocalAiCompletion
import com.maodouchat.core.model.LocalAiProvider
import com.maodouchat.core.model.Snapshot
import com.maodouchat.core.model.agentRewriteInstruction
import com.maodouchat.domain.messaging.AgentCompletionResultPolicy
import com.maodouchat.domain.messaging.AgentCompletionTextPolicy
import com.maodouchat.domain.messaging.normalizeAiRewriteMode

// 单轮文本直连调用：改写/翻译/摘要等走这里，不经过工具轮次。
object AgentTextCompletion {
    // K2 后端 bug：suspend 函数的函数类型默认参数（lambda 或函数引用都会）
    // 在 IR lowering 抛 "has no continuation"，所以默认实现改走重载显式传入。
    suspend fun completeText(
        provider: LocalAiProvider,
        instruction: String,
        userContent: String,
        onDelta: ((String) -> Unit)? = null,
        complete: suspend (
            LocalAiProvider,
            List<AgentChatMessage>,
            List<Map<String, Any?>>?,
            ((String) -> Unit)?
        ) -> LocalAiCompletion
    ): Result<String> {
        val messages = listOf(
            AgentChatMessage(role = "system", content = instruction),
            AgentChatMessage(role = "user", content = AgentCompletionTextPolicy.capUserContent(userContent))
        )
        return AgentCompletionResultPolicy.toResult(complete(provider, messages, null, onDelta))
    }

    suspend fun completeText(
        provider: LocalAiProvider,
        instruction: String,
        userContent: String,
        onDelta: ((String) -> Unit)? = null
    ): Result<String> =
        completeText(provider, instruction, userContent, onDelta) { p, messages, tools, delta ->
            OpenAiCompatClient.complete(p, messages, tools, delta)
        }

    suspend fun rewriteViaModel(
        provider: LocalAiProvider,
        args: Map<String, String>,
        complete: suspend (
            LocalAiProvider,
            List<AgentChatMessage>,
            List<Map<String, Any?>>?,
            ((String) -> Unit)?
        ) -> LocalAiCompletion
    ): String {
        val text = args["text"].orEmpty()
        if (text.isBlank()) return AgentCompletionResultPolicy.blankTextError()
        val mode = normalizeAiRewriteMode(args["mode"])
        return completeText(
            provider,
            agentRewriteInstruction(mode, args["targetLanguage"]),
            text,
            null,
            complete
        ).getOrElse { it.message ?: AgentCompletionResultPolicy.rewriteFailedFallback() }
    }

    fun styleHintFrom(snapshot: Snapshot): String? =
        AiWritingStylePolicy.rewriteStyleHint(snapshot)
}
