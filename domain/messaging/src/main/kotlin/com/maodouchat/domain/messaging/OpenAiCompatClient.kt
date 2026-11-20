package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentChatMessage
import com.maodouchat.domain.messaging.LocalAiCompletion
import com.maodouchat.domain.messaging.LocalAiProvider
import okhttp3.OkHttpClient

// 只和用户自配的 provider 对话：聊天明文绝不发到毛豆服务器。
// 实现已按簇拆到 OpenAiCompat{Chat,Vision,Audio}Client，这里只保留统一入口。
object OpenAiCompatClient {
    suspend fun complete(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        onDelta: ((String) -> Unit)? = null,
        httpClient: OkHttpClient
    ): LocalAiCompletion = OpenAiCompatChatClient.complete(provider, messages, tools, onDelta, httpClient)

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        imageBase64: String,
        mimeType: String,
        httpClient: OkHttpClient
    ): LocalAiCompletion = OpenAiCompatVisionClient.completeVision(provider, instruction, imageBase64, mimeType, httpClient)

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        images: List<Pair<String, String>>,
        httpClient: OkHttpClient
    ): LocalAiCompletion = OpenAiCompatVisionClient.completeVision(provider, instruction, images, httpClient)

    suspend fun transcribeAudio(
        provider: LocalAiProvider,
        audioBase64: String,
        mimeType: String,
        httpClient: OkHttpClient
    ): LocalAiCompletion = OpenAiCompatAudioClient.transcribeAudio(provider, audioBase64, mimeType, httpClient)

    internal fun parseNonStream(payload: String): LocalAiCompletion =
        OpenAiCompatVisionClient.parseNonStream(payload)
}
