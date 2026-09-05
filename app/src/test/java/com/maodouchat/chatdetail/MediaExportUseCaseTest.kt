package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.DefaultMediaExportUseCase
import com.maodouchat.ui.screen.chatdetail.MediaExportRequest
import com.maodouchat.ui.screen.chatdetail.MediaExportResult
import com.maodouchat.ui.screen.chatdetail.MediaExportUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaExportUseCaseTest {

    private class RecordingMediaExportUseCase : MediaExportUseCase {
        var lastSaveRequest: MediaExportRequest? = null
        var lastShareRequest: MediaExportRequest? = null
        var nextResult: MediaExportResult = MediaExportResult.Success

        override suspend fun saveToGallery(request: MediaExportRequest): MediaExportResult {
            lastSaveRequest = request
            if (request.isSecretChat) return MediaExportResult.SecretBlocked
            return nextResult
        }

        override suspend fun share(request: MediaExportRequest): MediaExportResult {
            lastShareRequest = request
            if (request.isSecretChat) return MediaExportResult.SecretBlocked
            return nextResult
        }
    }

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

    @Test
    fun saveMessageMedia_extractsCorrectMimeAndFileName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()

        val message = Message(
            id = "msg-1",
            chatId = "chat-1",
            senderId = "user-1",
            content = "file:///cache/photo.jpg",
            type = MessageType.IMAGE,
            meta = MessageMeta(fileName = "custom_name.jpg", fileMimeType = "image/jpeg"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)
        assertEquals(MediaExportResult.Success, result)
        assertTrue(result.isSuccess)

        val req = recorder.lastSaveRequest
        assertEquals("file:///cache/photo.jpg", req?.rawUri)
        assertEquals("image/jpeg", req?.mimeType)
        assertEquals("custom_name.jpg", req?.displayName)
        assertEquals(false, req?.isSecretChat)
    }

    @Test
    fun shareMessageMedia_blocksSecretChat() = runBlocking {
        val recorder = RecordingMediaExportUseCase()

        val secretMessage = Message(
            id = "msg-sec",
            chatId = "chat-sec",
            senderId = "user-2",
            content = "file:///cache/secret_photo.jpg",
            type = MessageType.IMAGE,
        )

        val result = recorder.shareMessageMedia(secretMessage, isSecretChat = true, chooserTitle = "Share Title")
        assertEquals(MediaExportResult.SecretBlocked, result)
        assertFalse(result.isSuccess)
        assertEquals(true, recorder.lastShareRequest?.isSecretChat)
    }

    @Test
    fun failureResult_handlesExceptions() {
        val ex = IllegalStateException("Disk full")
        val failure = MediaExportResult.Failure(ex)
        assertFalse(failure.isSuccess)
        assertEquals("Disk full", failure.cause?.message)
    }
}
