package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.model.Message

// 单张图片 OCR 门面：下载识别→落库两阶段的统一入口，全部透传。
object ImageOcrRecognition {

    suspend fun ocrAndPersist(
        context: Context,
        database: AppDatabase,
        message: Message,
        token: String,
        ownerUserId: String
    ): Boolean {
        val ocrText = try {
            ImageOcrRecognitionDownload.fetchOcrText(context, message, token)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return false
        return ImageOcrRecognitionPersist.persistOcrText(database, message, ocrText, ownerUserId)
    }
}
