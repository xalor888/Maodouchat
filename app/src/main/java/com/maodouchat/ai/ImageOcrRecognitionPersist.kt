package com.maodouchat.ai

import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.AiMessageResultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// OCR 落库簇：把识别文本写进 meta.aiImageAnalyses 并重算搜索索引；落库前后双查会话门闩。
internal object ImageOcrRecognitionPersist {

    suspend fun persistOcrText(
        database: AppDatabase,
        message: Message,
        ocrText: String,
        ownerUserId: String
    ): Boolean {
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
}
