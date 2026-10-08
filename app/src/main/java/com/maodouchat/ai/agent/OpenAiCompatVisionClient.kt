package com.maodouchat.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// 视觉簇：图片理解。原 OpenAiCompatClient 的 completeVision / parseNonStream 整体搬入，逻辑逐行不变。
object OpenAiCompatVisionClient {
    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        imageBase64: String,
        mimeType: String
    ): OpenAiCompatChatClient.Completion = completeVision(
        provider,
        instruction,
        listOf((mimeType.ifBlank { "image/jpeg" }) to imageBase64)
    )

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        images: List<Pair<String, String>>
    ): OpenAiCompatChatClient.Completion = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext OpenAiCompatChatClient.Completion.Error("没有可分析的图片")
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
            .post(body.toString().toRequestBody(OpenAiCompatHttp.jsonMedia))
        OpenAiCompatHttp.applyAuth(requestBuilder, provider)
        try {
            OpenAiCompatHttp.clientFor(provider).newCall(requestBuilder.build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext OpenAiCompatChatClient.Completion.Error(
                        "模型接口 ${response.code}: ${payload.take(240).ifBlank { response.message }}"
                    )
                }
                parseNonStream(payload)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            OpenAiCompatChatClient.Completion.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun parseNonStream(payload: String): OpenAiCompatChatClient.Completion =
        LocalAiProtocolCodec.parseChatCompletions(payload)
}
