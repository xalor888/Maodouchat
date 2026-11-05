package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUploadQueueModelsTest {

    @Test
    fun `statuses keep declared order`() {
        assertEquals(
            listOf(
                UploadStatus.IDLE,
                UploadStatus.QUEUED,
                UploadStatus.UPLOADING,
                UploadStatus.SUCCESS,
                UploadStatus.FAILED,
                UploadStatus.CANCELLED,
            ),
            UploadStatus.entries.toList(),
        )
    }

    @Test
    fun `new item defaults to queued with three retries`() {
        val item = UploadItem(id = "img-1", ownerUserId = "u1", base64Data = "AAAA")
        assertEquals(UploadStatus.QUEUED, item.status)
        assertEquals(null, item.uploadUrl)
        assertEquals(null, item.errorMessage)
        assertEquals(0, item.retryCount)
        assertEquals(3, item.maxRetries)
    }

    @Test
    fun `copy changes only the given fields`() {
        val item = UploadItem(id = "img-1", ownerUserId = "u1", base64Data = "AAAA")
        val done = item.copy(status = UploadStatus.SUCCESS, uploadUrl = "https://x/f.jpg")
        assertEquals(UploadStatus.SUCCESS, done.status)
        assertEquals("https://x/f.jpg", done.uploadUrl)
        assertEquals("img-1", done.id)
    }
}
