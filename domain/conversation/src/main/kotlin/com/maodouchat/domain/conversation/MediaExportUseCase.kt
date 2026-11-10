package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MediaExportRequest
import com.maodouchat.core.model.MediaExportResult
import com.maodouchat.core.model.MediaViewerPolicy
import com.maodouchat.core.model.Message

/**
 * 媒体下载/导出用例。
 * Media Center 只消费本地媒体投影；保存到系统相册及调用系统分享均统一经由此用例调度，
 * 封装密聊隐私门禁与媒体读取校验。
 */
interface MediaExportUseCase {
    suspend fun saveToGallery(request: MediaExportRequest): MediaExportResult
    suspend fun share(request: MediaExportRequest): MediaExportResult

    suspend fun saveMessageMedia(message: Message, isSecretChat: Boolean): MediaExportResult {
        val meta = message.parsedMeta()
        val mime = MediaViewerPolicy.defaultMime(message.type.name, meta.fileMimeType)
        val name = MediaViewerPolicy.defaultFileName(message.type.name, meta.fileName, mime)
        return saveToGallery(
            MediaExportRequest(
                rawUri = message.parsedContent(),
                mimeType = mime,
                displayName = name,
                isSecretChat = isSecretChat,
            )
        )
    }

    suspend fun shareMessageMedia(message: Message, isSecretChat: Boolean, chooserTitle: String): MediaExportResult {
        val meta = message.parsedMeta()
        val mime = MediaViewerPolicy.defaultMime(message.type.name, meta.fileMimeType)
        return share(
            MediaExportRequest(
                rawUri = message.parsedContent(),
                mimeType = mime,
                displayName = meta.fileName.orEmpty(),
                isSecretChat = isSecretChat,
                chooserTitle = chooserTitle,
            )
        )
    }
}
