package com.maodouchat.ai.agent

import com.maodouchat.ai.AiWritingStylePolicy

// K2 后端在 suspend 函数的默认参数 lambda 里直接调 suspend 会崩（has no continuation），
// 所以默认值用顶层私有函数的引用：语义与 lambda 逐字相同，避开编译器 bug。
private suspend fun defaultComplete(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    onDelta: ((String) -> Unit)?
): OpenAiCompatClient.Completion = OpenAiCompatClient.complete(provider, messages, tools, onDelta)

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
        ) -> OpenAiCompatClient.Completion = ::defaultComplete
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
        ) -> OpenAiCompatClient.Completion = ::defaultComplete
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
