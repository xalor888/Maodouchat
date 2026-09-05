package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import com.maodouchat.data.model.Message
import com.maodouchat.util.MediaExport
import com.maodouchat.util.MediaViewerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体导出请求参数。
 */
data class MediaExportRequest(
    val rawUri: String,
    val mimeType: String,
    val displayName: String,
    val isSecretChat: Boolean,
    val chooserTitle: String = "",
)

/**
 * 媒体导出执行结果。
 */
sealed interface MediaExportResult {
    data object Success : MediaExportResult
    data object SecretBlocked : MediaExportResult
    data class Failure(val cause: Throwable? = null) : MediaExportResult

    val isSuccess: Boolean get() = this is Success
}

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

class DefaultMediaExportUseCase(
    private val context: Context,
) : MediaExportUseCase {

    override suspend fun saveToGallery(request: MediaExportRequest): MediaExportResult {
        if (request.isSecretChat) {
            return MediaExportResult.SecretBlocked
        }
        return withContext(Dispatchers.IO) {
            try {
                val ok = MediaExport.saveToGallery(
                    context,
                    request.rawUri,
                    request.mimeType,
                    request.displayName,
                )
                if (ok) MediaExportResult.Success else MediaExportResult.Failure()
            } catch (e: Exception) {
                MediaExportResult.Failure(e)
            }
        }
    }

    override suspend fun share(request: MediaExportRequest): MediaExportResult {
        if (request.isSecretChat) {
            return MediaExportResult.SecretBlocked
        }
        return withContext(Dispatchers.IO) {
            try {
                val ok = MediaExport.share(
                    context,
                    request.rawUri,
                    request.mimeType,
                    request.chooserTitle,
                )
                if (ok) MediaExportResult.Success else MediaExportResult.Failure()
            } catch (e: Exception) {
                MediaExportResult.Failure(e)
            }
        }
    }
}
