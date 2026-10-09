package com.maodouchat.ai.agent

import com.maodouchat.ai.AiWritingStylePolicy

// 单轮文本直连调用：改写/翻译/摘要等走这里，不经过工具轮次。
object AgentTextCompletion {
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
        ) -> OpenAiCompatClient.Completion = { p, messages, tools, delta ->
            OpenAiCompatClient.complete(p, messages, tools, delta)
        }
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

    suspend fun rewriteViaModel(
        provider: LocalAiProvider,
        args: Map<String, String>,
        complete: suspend (
            LocalAiProvider,
            List<AgentChatMessage>,
            List<Map<String, Any?>>?,
            ((String) -> Unit)?
        ) -> OpenAiCompatClient.Completion = { p, messages, tools, delta ->
            OpenAiCompatClient.complete(p, messages, tools, delta)
        }
    ): String {
        val text = args["text"].orEmpty()
        if (text.isBlank()) return "Error: text required"
        val mode = args["mode"] ?: "polish"
        return completeText(
            provider,
            agentRewriteInstruction(mode, args["targetLanguage"]),
            text,
            complete = complete
        ).getOrElse { it.message ?: "rewrite failed" }
    }

    fun styleHintFrom(snapshot: AiWritingStylePolicy.Snapshot): String? =
        AiWritingStylePolicy.rewriteStyleHint(snapshot)
}
