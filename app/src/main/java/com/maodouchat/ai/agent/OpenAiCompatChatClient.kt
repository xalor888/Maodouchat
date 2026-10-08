package com.maodouchat.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 对话簇：纯文本聊天 + 工具调用。原 OpenAiCompatClient 的 complete 整体搬入，逻辑逐行不变。
object OpenAiCompatChatClient {
    sealed interface Completion {
        data class Text(val content: String) : Completion
        data class Tools(val calls: List<AgentToolCall>, val content: String) : Completion
        data class Error(val message: String) : Completion
    }

    suspend fun complete(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        onDelta: ((String) -> Unit)? = null
    ): Completion = withContext(Dispatchers.IO) {
        val stream = provider.stream && onDelta != null && tools.isNullOrEmpty()
        val (url, body) = when (provider.protocol) {
            LocalAiProtocol.OPENAI_CHAT_COMPLETIONS ->
                provider.resolvedChatCompletionsUrl() to LocalAiProtocolCodec.chatCompletionsBody(provider, messages, tools, stream)
            LocalAiProtocol.OPENAI_RESPONSES ->
                provider.resolvedResponsesUrl() to LocalAiProtocolCodec.responsesBody(provider, messages, tools, stream)
            LocalAiProtocol.ANTHROPIC_MESSAGES ->
                provider.resolvedAnthropicUrl() to LocalAiProtocolCodec.anthropicBody(provider, messages, tools, stream)
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(OpenAiCompatHttp.jsonMedia))
        OpenAiCompatHttp.applyAuth(requestBuilder, provider)
        try {
            OpenAiCompatHttp.clientFor(provider).newCall(requestBuilder.build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Completion.Error(
                        "模型接口 ${response.code}: ${payload.take(240).ifBlank { response.message }}"
                    )
                }
                if (stream) {
                    val deltaSink = onDelta ?: return@withContext parseByProtocol(provider.protocol, payload)
                    val streamed = LocalAiProtocolCodec.parseSseChat(payload, deltaSink)
                    if (streamed is Completion.Error) {
                        return@withContext parseByProtocol(provider.protocol, payload)
                    }
                    return@withContext streamed
                }
                parseByProtocol(provider.protocol, payload)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Completion.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun parseByProtocol(protocol: LocalAiProtocol, payload: String): Completion = when (protocol) {
        LocalAiProtocol.OPENAI_CHAT_COMPLETIONS -> LocalAiProtocolCodec.parseChatCompletions(payload)
        LocalAiProtocol.OPENAI_RESPONSES -> LocalAiProtocolCodec.parseResponses(payload)
        LocalAiProtocol.ANTHROPIC_MESSAGES -> LocalAiProtocolCodec.parseAnthropic(payload)
    }
}
