package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.core.model.LocalAiCompletion
import com.maodouchat.core.model.PreparedDocumentKind
import com.maodouchat.domain.messaging.aiFileTaskInstruction
import com.maodouchat.domain.messaging.aiImageAnalysisInstruction
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
        return when (val result = OpenAiCompatClient.completeVision(provider, instruction, imageBase64, "image/jpeg")) {
            is LocalAiCompletion.Text -> Result.success(result.content.trim())
            is LocalAiCompletion.Tools -> Result.success(result.content.trim())
            is LocalAiCompletion.Error -> Result.failure(IllegalStateException(result.message))
        }
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
        return AgentTextCompletion.completeText(provider, instruction, decodedText.take(LocalAiFileAnalyzer.MAX_TEXT_CHARS))
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
        } ?: return Result.failure(IllegalStateException("不支持该文件类型"))
        val instruction = aiFileTaskInstruction(prepared.fileName, prepared.mimeType, mode, question)
        return when (prepared.kind) {
            PreparedDocumentKind.TEXT ->
                AgentTextCompletion.completeText(provider, instruction, prepared.text)
            PreparedDocumentKind.PDF_PAGES -> {
                if (!provider.hasVisionCapability()) {
                    return Result.failure(
                        IllegalStateException("当前模型 (${provider.model}) 不支持视觉多模态输入，请在「AI 与隐私」设置中开启多模态支持或配置视觉模型（如 GPT-4o / Claude-3 / Qwen-VL 等）")
                    )
                }
                val pages = prepared.pageJpegsBase64.map { "image/jpeg" to it }
                when (val result = OpenAiCompatClient.completeVision(provider, instruction, pages)) {
                    is LocalAiCompletion.Text -> Result.success(result.content.trim())
                    is LocalAiCompletion.Tools -> Result.success(result.content.trim())
                    is LocalAiCompletion.Error -> Result.failure(IllegalStateException(result.message))
                }
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
        return when (val result = OpenAiCompatClient.transcribeAudio(provider, audioBase64, mimeType)) {
            is LocalAiCompletion.Text -> Result.success(result.content.trim())
            is LocalAiCompletion.Tools -> Result.success(result.content.trim())
            is LocalAiCompletion.Error -> Result.failure(IllegalStateException(result.message))
        }
    }
}
