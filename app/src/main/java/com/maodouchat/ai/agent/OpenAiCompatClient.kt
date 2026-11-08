package com.maodouchat.ai.agent

import com.maodouchat.core.model.AgentChatMessage
import com.maodouchat.core.model.LocalAiCompletion
import com.maodouchat.core.model.LocalAiProvider

// 只和用户自配的 provider 对话：聊天明文绝不发到毛豆服务器。
// 实现已按簇拆到 OpenAiCompat{Chat,Vision,Audio}Client，这里只保留统一入口。
object OpenAiCompatClient {
    suspend fun complete(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        onDelta: ((String) -> Unit)? = null
    ): LocalAiCompletion = OpenAiCompatChatClient.complete(provider, messages, tools, onDelta)

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        imageBase64: String,
        mimeType: String
    ): LocalAiCompletion = OpenAiCompatVisionClient.completeVision(provider, instruction, imageBase64, mimeType)

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        images: List<Pair<String, String>>
    ): LocalAiCompletion = OpenAiCompatVisionClient.completeVision(provider, instruction, images)

    suspend fun transcribeAudio(
        provider: LocalAiProvider,
        audioBase64: String,
        mimeType: String
    ): LocalAiCompletion = OpenAiCompatAudioClient.transcribeAudio(provider, audioBase64, mimeType)

    internal fun parseNonStream(payload: String): LocalAiCompletion =
        OpenAiCompatVisionClient.parseNonStream(payload)
}
