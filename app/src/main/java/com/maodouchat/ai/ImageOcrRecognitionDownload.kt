package com.maodouchat.ai

import android.content.Context
import androidx.core.net.toUri
import com.maodouchat.data.model.Message
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// OCR 下载簇：图片落盘→base64→本机模型识别，异常一律吞成空串由调用方判定失败。
internal object ImageOcrRecognitionDownload {

    suspend fun fetchOcrText(context: Context, message: Message, token: String): String {
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
