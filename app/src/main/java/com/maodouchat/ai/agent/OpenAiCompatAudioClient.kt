package com.maodouchat.ai.agent

import com.maodouchat.core.model.LocalAiCompletion
import com.maodouchat.core.model.LocalAiProvider
import com.maodouchat.domain.messaging.AgentAudioRequestPolicy
import com.maodouchat.domain.messaging.AgentModelErrorPolicy
import com.maodouchat.domain.messaging.AgentTranscriptionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 音频簇：语音转写。原 OpenAiCompatClient 的 transcribeAudio 整体搬入，逻辑逐行不变。
object OpenAiCompatAudioClient {
    suspend fun transcribeAudio(
        provider: LocalAiProvider,
        audioBase64: String,
        mimeType: String
    ): LocalAiCompletion = withContext(Dispatchers.IO) {
        val bytes = runCatching { android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP) }
            .getOrNull()
            ?: return@withContext LocalAiCompletion.Error(AgentAudioRequestPolicy.DECODE_ERROR)
        val url = AgentAudioRequestPolicy.transcriptionUrl(provider.baseUrl)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart(
                "file",
                AgentAudioRequestPolicy.FILE_NAME,
                bytes.toRequestBody(AgentAudioRequestPolicy.defaultMime(mimeType).toMediaType())
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
                    return@withContext LocalAiCompletion.Error(
                        AgentModelErrorPolicy.formatHttpError("语音接口", response.code, payload, response.message)
                    )
                }
                val text = AgentTranscriptionPolicy.extractText(payload)
                if (text.isBlank()) LocalAiCompletion.Error(AgentAudioRequestPolicy.EMPTY_TEXT_ERROR) else LocalAiCompletion.Text(text)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            LocalAiCompletion.Error(error.message ?: error.javaClass.simpleName)
        }
    }
}
