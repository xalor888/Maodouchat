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
import org.junit.Assert.assertNull
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

    @Test
    fun saveMessageMedia_blocksSecretChatThroughDefaultMethod() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m-sec", chatId = "c", senderId = "u", content = "file:///x.jpg", type = MessageType.IMAGE)

        val result = recorder.saveMessageMedia(message, isSecretChat = true)

        assertEquals(MediaExportResult.SecretBlocked, result)
        assertFalse(result.isSuccess)
        assertEquals(true, recorder.lastSaveRequest?.isSecretChat)
    }

    @Test
    fun shareMessageMedia_propagatesChooserTitleAndEmptyDisplayNameWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE)

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "分享")

        assertEquals(MediaExportResult.Success, result)
        val req = recorder.lastShareRequest
        assertEquals("file:///cache/photo.jpg", req?.rawUri)
        assertEquals("image/jpeg", req?.mimeType)
        assertEquals("", req?.displayName)
        assertEquals("分享", req?.chooserTitle)
    }

    @Test
    fun saveMessageMedia_derivesFileNameFromPolicyWithoutExplicitName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m2", chatId = "c", senderId = "u", content = "file:///cache/clip.mp4", type = MessageType.VIDEO)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("video_") && name.endsWith(".mp4"))
    }

    @Test
    fun failureResult_withoutCauseHasNullCause() {
        val failure = MediaExportResult.Failure()
        assertFalse(failure.isSuccess)
        assertNull(failure.cause)
    }

    @Test
    fun resultSuccessStates_truthTable() {
        assertTrue(MediaExportResult.Success.isSuccess)
        assertFalse(MediaExportResult.SecretBlocked.isSuccess)
        assertFalse(MediaExportResult.Failure(null).isSuccess)
    }
}
