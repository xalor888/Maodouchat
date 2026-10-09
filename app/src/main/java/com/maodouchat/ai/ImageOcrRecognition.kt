package com.maodouchat.ai

import android.content.Context
import androidx.core.net.toUri
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 单张图片的 OCR 识别与结果持久化：下载图片→走本机模型识别→写回消息 meta 并重算搜索索引。
object ImageOcrRecognition {
    suspend fun ocrAndPersist(
        context: Context,
        database: AppDatabase,
        message: Message,
        token: String,
        ownerUserId: String
    ): Boolean {
        val ocrText = try {
            downloadAndOcr(context, message, token)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
            ?: return false
        if (ocrText.isBlank()) return false
        // 网络往返后、落库前复查会话——OCR 结果不得写进新账号/清库中的共享 DB。
        if (!ImageOcrGates.sessionGate(ownerUserId)) return false
        // 与手动 AI 分析一致：结果写入 meta.aiImageAnalyses["ocr"]（本地缓存 + 可同步），
        // 且立即重算搜索索引（AiMessageResultStore.commit 内部完成）。
        val plainText = message.parsedContent()
        val currentMeta = message.parsedMeta()
        val updatedMeta = currentMeta.copy(
            aiImageAnalyses = currentMeta.aiImageAnalyses + (ImageOcrAutoIndexer.OCR_MODE to ocrText),
            preferredImageAnalysisMode = ImageOcrAutoIndexer.OCR_MODE
        )
        val updated = message.copy(
            content = ImageOcrLocalImage.composeContentWithMeta(plainText, updatedMeta),
            meta = updatedMeta
        )
        return withContext(Dispatchers.IO) {
            if (!ImageOcrGates.sessionGate(ownerUserId)) return@withContext false
            try {
                AiMessageResultStore(database).commit(operationId = null, message = updated)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun downloadAndOcr(context: Context, message: Message, token: String): String {
        val local = withContext(Dispatchers.IO) {
            ImageOcrLocalImage.ensureLocalImage(context, message, token)
        } ?: return ""
        val base64 = withContext(Dispatchers.IO) {
            runCatching {
                ImagePicker.uriToBase64(
                    context = context,
                    uri = local.parsedContent().toUri(),
                    maxWidth = 1_024,
                    quality = 72
                )
            }.getOrNull()
        }
        if (base64.isNullOrBlank()) return ""
        return com.maodouchat.ai.agent.LocalAiGateway.analyzeImage(context, base64, ImageOcrAutoIndexer.OCR_MODE)
            .getOrNull()
            ?.trim()
            ?.take(ImageOcrAutoIndexer.OCR_RESULT_MAX_CHARS)
            .orEmpty()
    }
}
