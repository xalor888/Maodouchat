package com.maodouchat.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks only to the user-configured provider. Supports OpenAI Chat Completions,
 * OpenAI Responses, and Anthropic Messages. Never posts chat plaintext to Maodou /api/ai.
 */
object OpenAiCompatClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

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
            .post(body.toString().toRequestBody(jsonMedia))
        when (provider.protocol) {
            LocalAiProtocol.ANTHROPIC_MESSAGES -> {
                requestBuilder.addHeader("x-api-key", provider.apiKey)
                requestBuilder.addHeader("anthropic-version", provider.anthropicVersion.ifBlank { "2023-06-01" })
                if (provider.apiKey.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
                }
            }
            else -> {
                requestBuilder.addHeader("Authorization", "Bearer ${provider.apiKey}")
                if (provider.organization.isNotBlank()) {
                    requestBuilder.addHeader("OpenAI-Organization", provider.organization)
                }
            }
        }
        LocalAiProtocolCodec.extraHeaders(provider).forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        try {
            http(provider).newCall(requestBuilder.build()).execute().use { response ->
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

    private fun http(provider: LocalAiProvider): OkHttpClient {
        val timeout = provider.clampedTimeoutSeconds().toLong()
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        imageBase64: String,
        mimeType: String
    ): Completion = completeVision(
        provider,
        instruction,
        listOf((mimeType.ifBlank { "image/jpeg" }) to imageBase64)
    )

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        images: List<Pair<String, String>>
    ): Completion = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext Completion.Error("没有可分析的图片")
        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", instruction.take(2_000)))
        images.take(LocalAiFileAnalyzer.MAX_PDF_PAGES).forEach { (mimeType, imageBase64) ->
            val mime = mimeType.ifBlank { "image/jpeg" }
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:$mime;base64,$imageBase64")
                    )
            )
        }
        val user = JSONObject()
            .put("role", "user")
            .put("content", parts)
        val body = JSONObject()
            .put("model", provider.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "只根据图片作答。不要声称已发送或已删除消息。"))
                    .put(user)
            )
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
        applyAuth(requestBuilder, provider)
        try {
            http(provider).newCall(requestBuilder.build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Completion.Error(
                        "模型接口 ${response.code}: ${payload.take(240).ifBlank { response.message }}"
                    )
                }
                parseNonStream(payload)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Completion.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun transcribeAudio(
        provider: LocalAiProvider,
        audioBase64: String,
        mimeType: String
    ): Completion = withContext(Dispatchers.IO) {
        val bytes = runCatching { android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP) }
            .getOrNull()
            ?: return@withContext Completion.Error("无法解码语音")
        val url = provider.baseUrl.trimEnd('/') + "/audio/transcriptions"
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart(
                "file",
                "voice.m4a",
                bytes.toRequestBody((mimeType.ifBlank { "audio/mp4" }).toMediaType())
            )
            .build()
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
        applyAuth(requestBuilder, provider)
        try {
            http(provider).newCall(requestBuilder.build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Completion.Error(
                        "语音接口 ${response.code}: ${payload.take(240).ifBlank { response.message }}"
                    )
                }
                val text = runCatching { JSONObject(payload).optString("text") }.getOrNull().orEmpty()
                if (text.isBlank()) Completion.Error("语音接口没有 text") else Completion.Text(text)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Completion.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun parseNonStream(payload: String): Completion =
        LocalAiProtocolCodec.parseChatCompletions(payload)

    private fun applyAuth(builder: Request.Builder, provider: LocalAiProvider) {
        when (provider.protocol) {
            LocalAiProtocol.ANTHROPIC_MESSAGES -> {
                builder.addHeader("x-api-key", provider.apiKey)
                builder.addHeader("anthropic-version", provider.anthropicVersion.ifBlank { "2023-06-01" })
                if (provider.apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${provider.apiKey}")
            }
            else -> {
                builder.addHeader("Authorization", "Bearer ${provider.apiKey}")
                if (provider.organization.isNotBlank()) {
                    builder.addHeader("OpenAI-Organization", provider.organization)
                }
            }
        }
        LocalAiProtocolCodec.extraHeaders(provider).forEach { (k, v) -> builder.addHeader(k, v) }
    }
}
