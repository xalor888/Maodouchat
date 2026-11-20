package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.domain.messaging.PreparedDocumentKind
import com.maodouchat.domain.messaging.AgentCompletionResultPolicy
import com.maodouchat.domain.messaging.AgentFileTaskMessagePolicy
import com.maodouchat.domain.messaging.AgentTextCompletion
import com.maodouchat.domain.messaging.LocalAiFilePrepPolicy
import com.maodouchat.domain.messaging.OpenAiCompatClient
import com.maodouchat.domain.messaging.aiFileTaskInstruction
import com.maodouchat.domain.messaging.aiImageAnalysisInstruction
import com.maodouchat.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本机模型文件/视觉/音频簇：图片分析、文件文本/多页分析、语音转写。函数体从 LocalAiGateway 逐字搬出。
internal object LocalAiFileTasks {
    suspend fun analyzeImage(
        context: Context,
        imageBase64: String,
        mode: String
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val instruction = aiImageAnalysisInstruction(mode)
        return AgentCompletionResultPolicy.toResult(OpenAiCompatClient.completeVision(provider, instruction, imageBase64, "image/jpeg", HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())))
    }

    suspend fun analyzeFileText(
        context: Context,
        fileName: String,
        mimeType: String,
        decodedText: String,
        mode: String,
        question: String?
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val instruction = aiFileTaskInstruction(fileName, mimeType, mode, question)
        return AgentTextCompletion.completeText(provider, instruction, LocalAiFilePrepPolicy.truncateText(decodedText), httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong()))
    }

    suspend fun analyzeFile(
        context: Context,
        fileName: String,
        mimeType: String,
        fileBase64: String,
        mode: String,
        question: String?
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        val prepared = withContext(Dispatchers.Default) {
            LocalAiFileAnalyzer.prepare(fileName, mimeType, fileBase64)
        } ?: return Result.failure(IllegalStateException(AgentFileTaskMessagePolicy.unsupportedFileType()))
        val instruction = aiFileTaskInstruction(prepared.fileName, prepared.mimeType, mode, question)
        return when (prepared.kind) {
            PreparedDocumentKind.TEXT ->
                AgentTextCompletion.completeText(provider, instruction, prepared.text, httpClient = HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong()))
            PreparedDocumentKind.PDF_PAGES -> {
                if (!provider.hasVisionCapability()) {
                    return Result.failure(
                        IllegalStateException(AgentFileTaskMessagePolicy.visionUnsupported(provider.model))
                    )
                }
                val pages = prepared.pageJpegsBase64.map { "image/jpeg" to it }
                AgentCompletionResultPolicy.toResult(OpenAiCompatClient.completeVision(provider, instruction, pages, HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())))
            }
        }
    }

    suspend fun transcribe(
        context: Context,
        audioBase64: String,
        mimeType: String
    ): Result<String> {
        val provider = LocalAiProviderStore.activeProvider(context)
            ?: return Result.failure(IllegalStateException(LocalAiGateway.missingProviderMessage()))
        return AgentCompletionResultPolicy.toResult(OpenAiCompatClient.transcribeAudio(provider, audioBase64, mimeType, HttpClients.chatModel(provider.clampedTimeoutSeconds().toLong())))
    }
}
