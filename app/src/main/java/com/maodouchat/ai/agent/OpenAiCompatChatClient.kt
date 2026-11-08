package com.maodouchat.ai.agent

import com.maodouchat.core.model.AgentChatMessage
import com.maodouchat.core.model.LocalAiCompletion
import com.maodouchat.core.model.LocalAiProtocol
import com.maodouchat.core.model.LocalAiProtocolCodec
import com.maodouchat.core.model.LocalAiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 对话簇：纯文本聊天 + 工具调用。原 OpenAiCompatClient 的 complete 整体搬入，逻辑逐行不变。
object OpenAiCompatChatClient {

    suspend fun complete(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        onDelta: ((String) -> Unit)? = null
    ): LocalAiCompletion = withContext(Dispatchers.IO) {
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
                    return@withContext LocalAiCompletion.Error(
                        "模型接口 ${response.code}: ${payload.take(240).ifBlank { response.message }}"
                    )
                }
                if (stream) {
                    val deltaSink = onDelta ?: return@withContext parseByProtocol(provider.protocol, payload)
                    val streamed = LocalAiProtocolCodec.parseSseChat(payload, deltaSink)
                    if (streamed is LocalAiCompletion.Error) {
                        return@withContext parseByProtocol(provider.protocol, payload)
                    }
                    return@withContext streamed
                }
                parseByProtocol(provider.protocol, payload)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            LocalAiCompletion.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun parseByProtocol(protocol: LocalAiProtocol, payload: String): LocalAiCompletion = when (protocol) {
        LocalAiProtocol.OPENAI_CHAT_COMPLETIONS -> LocalAiProtocolCodec.parseChatCompletions(payload)
        LocalAiProtocol.OPENAI_RESPONSES -> LocalAiProtocolCodec.parseResponses(payload)
        LocalAiProtocol.ANTHROPIC_MESSAGES -> LocalAiProtocolCodec.parseAnthropic(payload)
    }
}
