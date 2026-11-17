package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.core.model.JsonFormat
import com.maodouchat.core.model.Message

// 自动 OCR 纯逻辑：常量、已索引判定、结果写回变换、结果截断。
// 原在 app 侧 ImageOcrAutoIndexer 伴生对象与 ImageOcrScan/ImageOcrRecognitionPersist/ImageOcrRecognitionDownload，逐字迁入。
object ImageOcrPolicy {

    val OCR_MODE: String = AiImageAnalysisMode.OCR.wireValue

    const val OCR_RESULT_MAX_CHARS = 6_000

    const val OCR_IMAGES_PER_RUN = 10

    const val OCR_SCAN_WINDOW = 200

    fun isAlreadyIndexed(message: Message): Boolean =
        message.parsedMeta().aiImageAnalyses.containsKey(OCR_MODE)

    fun withOcrResult(message: Message, ocrText: String): Message {
        val currentMeta = message.parsedMeta()
        val updatedMeta = currentMeta.copy(
            aiImageAnalyses = currentMeta.aiImageAnalyses + (OCR_MODE to ocrText),
            preferredImageAnalysisMode = OCR_MODE
        )
        return message.copy(
            content = JsonFormat.composeContentWithMeta(message.parsedContent(), updatedMeta),
            meta = updatedMeta
        )
    }

    fun truncateOcrResult(text: String): String = text.trim().take(OCR_RESULT_MAX_CHARS)
}
