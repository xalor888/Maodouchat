package com.maodouchat.ai.agent

import com.maodouchat.ai.AiWritingStylePolicy

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
        ) -> OpenAiCompatClient.Completion
    ): Result<String> {
        val messages = listOf(
            AgentChatMessage(role = "system", content = instruction),
            AgentChatMessage(role = "user", content = userContent.take(8_000))
        )
        return when (val result = complete(provider, messages, null, onDelta)) {
            is OpenAiCompatClient.Completion.Text -> Result.success(result.content.trim())
            is OpenAiCompatClient.Completion.Tools -> Result.success(result.content.trim())
            is OpenAiCompatClient.Completion.Error -> Result.failure(IllegalStateException(result.message))
        }
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
        ) -> OpenAiCompatClient.Completion
    ): String {
        val text = args["text"].orEmpty()
        if (text.isBlank()) return "Error: text required"
        val mode = args["mode"] ?: "polish"
        return completeText(
            provider,
            agentRewriteInstruction(mode, args["targetLanguage"]),
            text,
            null,
            complete
        ).getOrElse { it.message ?: "rewrite failed" }
    }

    fun styleHintFrom(snapshot: AiWritingStylePolicy.Snapshot): String? =
        AiWritingStylePolicy.rewriteStyleHint(snapshot)
}
