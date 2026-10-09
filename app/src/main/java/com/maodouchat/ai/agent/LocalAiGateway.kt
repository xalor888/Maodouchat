package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.network.AiContextMessage

// 本机模型统一入口：薄门面，公开 API 不动，任务实现已按簇搬到 LocalAiTextTasks / LocalAiFileTasks / LocalAiLocalRanking。
object LocalAiGateway {
    fun configured(context: Context): Boolean = LocalAiProviderStore.isConfigured(context)

    fun missingProviderMessage(): String =
        "请先在设置 → AI 与隐私中填写本机模型（OpenAI Completions / Responses 或 Anthropic）、Key 和上下文。聊天明文不会发到毛豆服务器。"

    suspend fun rewrite(
        context: Context,
        text: String,
        mode: String,
        targetLanguage: String?,
        onDelta: ((String) -> Unit)? = null
    ): Result<String> = LocalAiTextTasks.rewrite(context, text, mode, targetLanguage, onDelta)

    suspend fun translate(context: Context, text: String, targetLanguage: String): Result<String> =
        LocalAiTextTasks.translate(context, text, targetLanguage)

    suspend fun suggestReplies(
        context: Context,
        messages: List<AiContextMessage>,
        tone: String,
        count: Int,
        onDelta: ((String) -> Unit)? = null
    ): Result<List<String>> = LocalAiTextTasks.suggestReplies(context, messages, tone, count, onDelta)

    suspend fun summarize(
        context: Context,
        messages: List<AiContextMessage>,
        style: String
    ): Result<String> = LocalAiTextTasks.summarize(context, messages, style)

    suspend fun groupAssistant(
        context: Context,
        query: String,
        messages: List<AiContextMessage>,
        mode: String
    ): Result<String> = LocalAiTextTasks.groupAssistant(context, query, messages, mode)

    suspend fun rankSemantic(
        query: String,
        candidates: List<Pair<String, String>>
    ): List<String> = LocalAiLocalRanking.rankSemantic(query, candidates)

    suspend fun rankSemanticScored(
        query: String,
        candidates: List<Pair<String, String>>
    ): List<Pair<String, Double>> = LocalAiLocalRanking.rankSemanticScored(query, candidates)

    suspend fun analyzeImage(
        context: Context,
        imageBase64: String,
        mode: String
    ): Result<String> = LocalAiFileTasks.analyzeImage(context, imageBase64, mode)

    suspend fun analyzeFileText(
        context: Context,
        fileName: String,
        mimeType: String,
        decodedText: String,
        mode: String,
        question: String?
    ): Result<String> = LocalAiFileTasks.analyzeFileText(context, fileName, mimeType, decodedText, mode, question)

    suspend fun analyzeFile(
        context: Context,
        fileName: String,
        mimeType: String,
        fileBase64: String,
        mode: String,
        question: String?
    ): Result<String> = LocalAiFileTasks.analyzeFile(context, fileName, mimeType, fileBase64, mode, question)

    suspend fun transcribe(
        context: Context,
        audioBase64: String,
        mimeType: String
    ): Result<String> = LocalAiFileTasks.transcribe(context, audioBase64, mimeType)
}
