package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentModelErrorPolicy
import com.maodouchat.domain.messaging.AgentVisionRequestPolicy
import com.maodouchat.domain.messaging.LocalAiCompletion
import com.maodouchat.domain.messaging.LocalAiProtocolCodec
import com.maodouchat.domain.messaging.LocalAiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 视觉簇：图片理解。原 OpenAiCompatClient 的 completeVision / parseNonStream 整体搬入，逻辑逐行不变。
object OpenAiCompatVisionClient {
    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        imageBase64: String,
        mimeType: String,
        httpClient: OkHttpClient
    ): LocalAiCompletion = completeVision(
        provider,
        instruction,
        listOf((AgentVisionRequestPolicy.defaultMime(mimeType)) to imageBase64),
        httpClient
    )

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        images: List<Pair<String, String>>,
        httpClient: OkHttpClient
    ): LocalAiCompletion = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext LocalAiCompletion.Error(AgentVisionRequestPolicy.EMPTY_IMAGES_ERROR)
        val url = AgentVisionRequestPolicy.visionUrl(provider.baseUrl)
        val body = AgentVisionRequestPolicy.buildVisionBody(
            provider.model, instruction, images, LocalAiPdfPagePolicy.MAX_PDF_PAGES
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(OpenAiCompatHttp.jsonMedia))
        OpenAiCompatHttp.applyAuth(requestBuilder, provider)
        try {
            OpenAiCompatHttp.clientFor(provider, httpClient).newCall(requestBuilder.build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LocalAiCompletion.Error(
                        AgentModelErrorPolicy.formatHttpError("模型接口", response.code, payload, response.message)
                    )
                }
                parseNonStream(payload)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            LocalAiCompletion.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun parseNonStream(payload: String): LocalAiCompletion =
        LocalAiProtocolCodec.parseChatCompletions(payload)
}
