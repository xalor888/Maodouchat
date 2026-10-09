package com.maodouchat.ai.agent

// 只和用户自配的 provider 对话：聊天明文绝不发到毛豆服务器。
// 实现已按簇拆到 OpenAiCompat{Chat,Vision,Audio}Client，这里只保留统一入口。
object OpenAiCompatClient {
    // 结果类型留在统一入口：typealias 不能当嵌套类限定符用
    //（`is OpenAiCompatClient.Completion.Error` 会 Unresolved），簇客户端引用这里的嵌套类型。
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
    ): Completion = OpenAiCompatChatClient.complete(provider, messages, tools, onDelta)

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        imageBase64: String,
        mimeType: String
    ): Completion = OpenAiCompatVisionClient.completeVision(provider, instruction, imageBase64, mimeType)

    suspend fun completeVision(
        provider: LocalAiProvider,
        instruction: String,
        images: List<Pair<String, String>>
    ): Completion = OpenAiCompatVisionClient.completeVision(provider, instruction, images)

    suspend fun transcribeAudio(
        provider: LocalAiProvider,
        audioBase64: String,
        mimeType: String
    ): Completion = OpenAiCompatAudioClient.transcribeAudio(provider, audioBase64, mimeType)

    internal fun parseNonStream(payload: String): Completion =
        OpenAiCompatVisionClient.parseNonStream(payload)
}
