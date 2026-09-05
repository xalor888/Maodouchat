package com.maodouchat.explore.repository

import com.maodouchat.network.AttachmentUploadResponse
import com.maodouchat.network.AttachmentUploadStatusResponse
import com.maodouchat.network.api.MediaApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploadQueueTest {

    private lateinit var fakeMediaApi: FakeMediaApi
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var queue: DefaultMediaUploadQueue

    @Before
    fun setUp() {
        fakeMediaApi = FakeMediaApi()
        queue = DefaultMediaUploadQueue(
            mediaApi = fakeMediaApi,
            dispatcher = testDispatcher,
            scope = testScope,
            sessionGateCheck = { _, _ -> true }
        )
    }

    @Test
    fun enqueue_addsItemWithQueuedStatus() {
        queue.enqueue(id = "img-1", ownerUserId = "user-1", base64Data = "base64-data")

        val items = queue.itemsFlow.value
        assertEquals(1, items.size)
        val item = items["img-1"]
        assertNotNull(item)
        assertEquals(UploadStatus.QUEUED, item?.status)
        assertEquals("base64-data", item?.base64Data)
    }

    @Test
    fun startUpload_success_updatesStatusToSuccessWithUrl() = runTest(testDispatcher) {
        fakeMediaApi.uploadPostImageResult = Result.success("https://cdn.example.com/post1.jpg")
        queue.enqueue(id = "img-1", ownerUserId = "user-1", base64Data = "base64-data")

        queue.startUpload("img-1", "token-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val item = queue.itemsFlow.value["img-1"]
        assertEquals(UploadStatus.SUCCESS, item?.status)
        assertEquals("https://cdn.example.com/post1.jpg", item?.uploadUrl)
    }

    @Test
    fun startUpload_failure_updatesStatusToFailedAndIncrementsRetry() = runTest(testDispatcher) {
        fakeMediaApi.uploadPostImageResult = Result.failure(IOException("Server timeout"))
        queue.enqueue(id = "img-1", ownerUserId = "user-1", base64Data = "base64-data")

        queue.startUpload("img-1", "token-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val item = queue.itemsFlow.value["img-1"]
        assertEquals(UploadStatus.FAILED, item?.status)
        assertEquals(1, item?.retryCount)
        assertEquals("Server timeout", item?.errorMessage)
    }

    @Test
    fun retry_resetsStatusAndRetriesSuccessfully() = runTest(testDispatcher) {
        fakeMediaApi.uploadPostImageResult = Result.failure(IOException("Transient error"))
        queue.enqueue(id = "img-1", ownerUserId = "user-1", base64Data = "base64-data")

        queue.startUpload("img-1", "token-1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(UploadStatus.FAILED, queue.itemsFlow.value["img-1"]?.status)

        fakeMediaApi.uploadPostImageResult = Result.success("https://cdn.example.com/post2.jpg")
        queue.retry("img-1", "token-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val item = queue.itemsFlow.value["img-1"]
        assertEquals(UploadStatus.SUCCESS, item?.status)
        assertEquals("https://cdn.example.com/post2.jpg", item?.uploadUrl)
    }

    @Test
    fun sessionGateRejected_failsWithoutUploading() = runTest(testDispatcher) {
        val gatedQueue = DefaultMediaUploadQueue(
            mediaApi = fakeMediaApi,
            dispatcher = testDispatcher,
            scope = testScope,
            sessionGateCheck = { _, _ -> false }
        )

        gatedQueue.enqueue(id = "img-1", ownerUserId = "user-1", base64Data = "data")
        gatedQueue.startUpload("img-1", "token-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val item = gatedQueue.itemsFlow.value["img-1"]
        assertEquals(UploadStatus.FAILED, item?.status)
        assertTrue(fakeMediaApi.uploadPostImageCalls.isEmpty())
    }

    @Test
    fun cancel_cancelsActiveUpload() = runTest(testDispatcher) {
        queue.enqueue(id = "img-1", ownerUserId = "user-1", base64Data = "data")
        queue.cancel("img-1")

        val item = queue.itemsFlow.value["img-1"]
        assertEquals(UploadStatus.CANCELLED, item?.status)
    }

    private class FakeMediaApi : MediaApi {
        var uploadPostImageResult: Result<String> = Result.success("url")
        var discardPostImageResult: Result<Unit> = Result.success(Unit)
        val uploadPostImageCalls = mutableListOf<String>()
        val discardPostImageCalls = mutableListOf<String>()

        override suspend fun uploadPostImage(token: String, base64Data: String): Result<String> {
            uploadPostImageCalls.add(base64Data)
            return uploadPostImageResult
        }

        override suspend fun discardPostImage(token: String, imageUrl: String): Result<Unit> {
            discardPostImageCalls.add(imageUrl)
            return discardPostImageResult
        }

        override suspend fun uploadEncryptedAttachment(token: String, chatId: String, messageId: String, encryptedFile: File, cipherSha256: String, onProgress: (Long, Long) -> Unit, onCheckpoint: suspend (String, Long, Long) -> Unit) = Result.failure<AttachmentUploadResponse>(UnsupportedOperationException())
        override suspend fun verifyEncryptedAttachmentReady(token: String, chatId: String, messageId: String, attachmentId: String, expectedSha256: String, expectedSize: Long) = Result.failure<AttachmentUploadStatusResponse>(UnsupportedOperationException())
        override suspend fun deleteUncommittedAttachment(token: String, attachmentId: String) = Result.success(Unit)
        override suspend fun downloadEncryptedAttachment(token: String, attachmentId: String, expectedSha256: String, expectedSize: Long, target: File, onProgress: (Long, Long) -> Unit) = Result.success(Unit)
        override suspend fun downloadPostImage(token: String, imageUrl: String, target: File) = Result.success(Unit)
        override suspend fun uploadAvatar(token: String, base64Data: String) = Result.success("avatar")
        override suspend fun removeAvatar(token: String) = Result.success(Unit)
        override suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String) = Result.success("group-avatar")
    }
}
