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

    @Test
    fun shareMessageMedia_extractsMimeFromMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1",
            chatId = "c",
            senderId = "u",
            content = "file:///cache/clip.mp4",
            type = MessageType.VIDEO,
            meta = MessageMeta(fileMimeType = "video/mp4"),
        )

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "分享视频")

        assertEquals(MediaExportResult.Success, result)
        val req = recorder.lastShareRequest
        assertEquals("video/mp4", req?.mimeType)
        assertEquals("分享视频", req?.chooserTitle)
    }

    @Test
    fun saveMessageMedia_propagatesFailureResult() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        recorder.nextResult = MediaExportResult.Failure(IllegalStateException("boom"))
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///x.jpg", type = MessageType.IMAGE)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertTrue(result is MediaExportResult.Failure)
        assertFalse(result.isSuccess)
        assertEquals("boom", (result as MediaExportResult.Failure).cause?.message)
    }

    @Test
    fun saveMessageMedia_derivesGifMimeAndNameFromTypeWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m2", chatId = "c", senderId = "u", content = "file:///cache/a.gif", type = MessageType.GIF)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/gif", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("gif_") && name.endsWith(".gif"))
    }

    @Test
    fun saveMessageMedia_fallsBackToOctetStreamForFileWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m3", chatId = "c", senderId = "u", content = "file:///cache/doc", type = MessageType.FILE)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".bin"))
    }

    @Test
    fun shareMessageMedia_secretRequestStillRecordsChooserTitle() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m-sec", chatId = "c", senderId = "u", content = "file:///x.jpg", type = MessageType.IMAGE)

        val result = recorder.shareMessageMedia(message, isSecretChat = true, chooserTitle = "Share Title")

        assertEquals(MediaExportResult.SecretBlocked, result)
        assertEquals(true, recorder.lastShareRequest?.isSecretChat)
        assertEquals("Share Title", recorder.lastShareRequest?.chooserTitle)
    }


    @Test
    fun saveMessageMedia_derivesVoiceMimeOctetAndMediaName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///cache/voice.mp3", type = MessageType.VOICE)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".bin"))
    }

    @Test
    fun saveMessageMedia_explicitBlankMimeFallsBackToTypeDefault() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE,
            meta = MessageMeta(fileMimeType = "   "),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/jpeg", recorder.lastSaveRequest?.mimeType)
    }

    @Test
    fun saveMessageMedia_explicitBlankFileNameFallsBackToDerived() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE,
            meta = MessageMeta(fileName = "  "),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("image_") && name.endsWith(".jpg"))
    }

    @Test
    fun shareMessageMedia_usesMetaFileNameEvenForVideo() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/clip.mp4", type = MessageType.VIDEO,
            meta = MessageMeta(fileName = "clip.mp4", fileMimeType = "video/mp4"),
        )

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("clip.mp4", recorder.lastShareRequest?.displayName)
        assertEquals("video/mp4", recorder.lastShareRequest?.mimeType)
    }

    @Test
    fun shareMessageMedia_propagatesFailureResult() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        recorder.nextResult = MediaExportResult.Failure(IllegalStateException("share boom"))
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///x.jpg", type = MessageType.IMAGE)

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertTrue(result is MediaExportResult.Failure)
        assertFalse(result.isSuccess)
        assertEquals("share boom", (result as MediaExportResult.Failure).cause?.message)
    }

    @Test
    fun saveMessageMedia_derivesOctetStreamAndMediaNameForStickerWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///cache/sticker.webp", type = MessageType.STICKER)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".bin"))
    }

    @Test
    fun saveMessageMedia_usesMetaPngMimeAndDerivesPngName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/pic.png", type = MessageType.IMAGE,
            meta = MessageMeta(fileMimeType = "image/png"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/png", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("image_") && name.endsWith(".png"))
    }

    @Test
    fun saveMessageMedia_trimsPaddedExplicitFileName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/pic.png", type = MessageType.IMAGE,
            meta = MessageMeta(fileName = "  clip.png  ", fileMimeType = "image/png"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("clip.png", recorder.lastSaveRequest?.displayName)
    }

    @Test
    fun shareMessageMedia_keepsBlankMetaFileNameVerbatim() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE,
            meta = MessageMeta(fileName = "  "),
        )

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("  ", recorder.lastShareRequest?.displayName)
    }

    @Test
    fun saveMessageMedia_derivesOctetStreamAndMediaNameForTextWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "hello", type = MessageType.TEXT)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("hello", recorder.lastSaveRequest?.rawUri)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".bin"))
    }

    @Test
    fun saveMessageMedia_usesExplicitFileNameWithDerivedMime() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE,
            meta = MessageMeta(fileName = "photo.png"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/jpeg", recorder.lastSaveRequest?.mimeType)
        assertEquals("photo.png", recorder.lastSaveRequest?.displayName)
    }

    @Test
    fun shareMessageMedia_derivesOctetStreamAndEmptyNameForTextWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "hello", type = MessageType.TEXT)

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("hello", recorder.lastShareRequest?.rawUri)
        assertEquals("application/octet-stream", recorder.lastShareRequest?.mimeType)
        assertEquals("", recorder.lastShareRequest?.displayName)
    }

    @Test
    fun saveMessageMedia_derivesOctetStreamAndMediaNameForLocationWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///cache/loc", type = MessageType.LOCATION)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".bin"))
    }

    @Test
    fun shareMessageMedia_blankMetaMimeFallsBackToTypeDefault() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE,
            meta = MessageMeta(fileMimeType = "   "),
        )

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/jpeg", recorder.lastShareRequest?.mimeType)
        assertEquals("", recorder.lastShareRequest?.displayName)
    }

    @Test
    fun saveMessageMedia_unescapesEntityInContentForRawUri() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "fish &amp; chips", type = MessageType.TEXT)

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("fish & chips", recorder.lastSaveRequest?.rawUri)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
    }

    @Test
    fun shareMessageMedia_unescapesEntityInContentForRawUri() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "fish &amp; chips", type = MessageType.TEXT)

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("fish & chips", recorder.lastShareRequest?.rawUri)
        assertEquals("application/octet-stream", recorder.lastShareRequest?.mimeType)
    }

    @Test
    fun shareMessageMedia_derivesOctetStreamAndEmptyNameForVoiceWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///cache/voice", type = MessageType.VOICE)

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/octet-stream", recorder.lastShareRequest?.mimeType)
        assertEquals("", recorder.lastShareRequest?.displayName)
    }

    @Test
    fun shareMessageMedia_derivesGifMimeAndEmptyNameWithoutMeta() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(id = "m1", chatId = "c", senderId = "u", content = "file:///cache/anim.gif", type = MessageType.GIF)

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/gif", recorder.lastShareRequest?.mimeType)
        assertEquals("", recorder.lastShareRequest?.displayName)
    }

    @Test
    fun saveMessageMedia_derivesWebpNameFromExplicitMimeWithoutFileName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/sticker.webp", type = MessageType.STICKER,
            meta = MessageMeta(fileMimeType = "image/webp"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/webp", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".webp"))
    }

    @Test
    fun saveMessageMedia_usesExplicitFileNameWithOctetStreamForFileWithBlankMime() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/report.pdf", type = MessageType.FILE,
            meta = MessageMeta(fileName = "report.pdf", fileMimeType = "   "),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/octet-stream", recorder.lastSaveRequest?.mimeType)
        assertEquals("report.pdf", recorder.lastSaveRequest?.displayName)
    }

    @Test
    fun saveMessageMedia_keepsExplicitQuickTimeMimeButDerivesMp4Name() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/clip.mov", type = MessageType.VIDEO,
            meta = MessageMeta(fileMimeType = "video/quicktime"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("video/quicktime", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("video_") && name.endsWith(".mp4"))
    }

    @Test
    fun saveMessageMedia_uppercaseGifMimeStillDerivesGifName() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/anim.gif", type = MessageType.IMAGE,
            meta = MessageMeta(fileMimeType = "image/GIF"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/GIF", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("image_") && name.endsWith(".gif"))
    }

    @Test
    fun shareMessageMedia_keepsPaddedExplicitFileNameVerbatim() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/photo.jpg", type = MessageType.IMAGE,
            meta = MessageMeta(fileName = "  pic.jpg  "),
        )

        val result = recorder.shareMessageMedia(message, isSecretChat = false, chooserTitle = "Share")

        assertEquals(MediaExportResult.Success, result)
        assertEquals("  pic.jpg  ", recorder.lastShareRequest?.displayName)
    }

    @Test
    fun saveMessageMedia_explicitPdfMimeStillDerivesBinNameForFile() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/report.pdf", type = MessageType.FILE,
            meta = MessageMeta(fileMimeType = "application/pdf"),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("application/pdf", recorder.lastSaveRequest?.mimeType)
        val name = recorder.lastSaveRequest?.displayName.orEmpty()
        assertTrue(name.startsWith("media_") && name.endsWith(".bin"))
    }

    @Test
    fun saveMessageMedia_explicitGifNameWithBlankMimeFallsBackToGifMime() = runBlocking {
        val recorder = RecordingMediaExportUseCase()
        val message = Message(
            id = "m1", chatId = "c", senderId = "u", content = "file:///cache/anim.gif", type = MessageType.GIF,
            meta = MessageMeta(fileName = "anim.gif", fileMimeType = "   "),
        )

        val result = recorder.saveMessageMedia(message, isSecretChat = false)

        assertEquals(MediaExportResult.Success, result)
        assertEquals("image/gif", recorder.lastSaveRequest?.mimeType)
        assertEquals("anim.gif", recorder.lastSaveRequest?.displayName)
    }

}
