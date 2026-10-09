package com.maodouchat.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// 音频簇：语音转写。原 OpenAiCompatClient 的 transcribeAudio 整体搬入，逻辑逐行不变。
object OpenAiCompatAudioClient {
    suspend fun transcribeAudio(
        provider: LocalAiProvider,
        audioBase64: String,
        mimeType: String
    ): OpenAiCompatClient.Completion = withContext(Dispatchers.IO) {
        val bytes = runCatching { android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP) }
            .getOrNull()
            ?: return@withContext OpenAiCompatClient.Completion.Error("无法解码语音")
        val url = provider.baseUrl.trimEnd('/') + "/audio/transcriptions"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
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
        OpenAiCompatHttp.applyAuth(requestBuilder, provider)
        try {
            OpenAiCompatHttp.clientFor(provider).newCall(requestBuilder.build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext OpenAiCompatClient.Completion.Error(
                        "语音接口 ${response.code}: ${payload.take(240).ifBlank { response.message }}"
                    )
                }
                val text = runCatching { JSONObject(payload).optString("text") }.getOrNull().orEmpty()
                if (text.isBlank()) OpenAiCompatClient.Completion.Error("语音接口没有 text") else OpenAiCompatClient.Completion.Text(text)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            OpenAiCompatClient.Completion.Error(error.message ?: error.javaClass.simpleName)
        }
    }
}
