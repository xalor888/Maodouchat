package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import com.maodouchat.core.model.MediaExportRequest
import com.maodouchat.core.model.MediaExportResult
import com.maodouchat.domain.conversation.MediaExportUseCase
import com.maodouchat.util.MediaExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
