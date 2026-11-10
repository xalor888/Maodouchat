package com.maodouchat.chatdetail

import com.maodouchat.core.model.MediaExportRequest
import com.maodouchat.core.model.MediaExportResult
import com.maodouchat.ui.screen.chatdetail.DefaultMediaExportUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultMediaExportUseCaseTest {

    @Test
    fun defaultMediaExportUseCase_blocksSecretChatImmediately() = runBlocking {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val useCase = DefaultMediaExportUseCase(dummyContext)

        val saveResult = useCase.saveToGallery(
            MediaExportRequest(
                rawUri = "file:///secret/image.jpg",
                mimeType = "image/jpeg",
                displayName = "secret.jpg",
                isSecretChat = true,
            )
        )
        assertEquals(MediaExportResult.SecretBlocked, saveResult)
        assertFalse(saveResult.isSuccess)

        val shareResult = useCase.share(
            MediaExportRequest(
                rawUri = "file:///secret/image.jpg",
                mimeType = "image/jpeg",
                displayName = "secret.jpg",
                isSecretChat = true,
                chooserTitle = "Share",
            )
        )
        assertEquals(MediaExportResult.SecretBlocked, shareResult)
        assertFalse(shareResult.isSuccess)
    }

}
