package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageStatus
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDetailBookkeepingTest {

    @Test
    fun liveLocationDefaultsEmpty() {
        val bookkeeping = ChatDetailLiveLocationBookkeeping()
        assertNull(bookkeeping.liveLocationJob)
        assertNull(bookkeeping.liveLocationCancel)
        assertNull(bookkeeping.lastLiveLocationPayload)
        assertNotNull(bookkeeping.liveLocationUpdateMutex)
    }

    @Test
    fun liveLocationHoldsPayloadAndJob() {
        val bookkeeping = ChatDetailLiveLocationBookkeeping()
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, live = true)
        bookkeeping.lastLiveLocationPayload = payload
        bookkeeping.liveLocationCancel = { }
        val job = Job()
        bookkeeping.liveLocationJob = job
        assertEquals(payload, bookkeeping.lastLiveLocationPayload)
        assertEquals(job, bookkeeping.liveLocationJob)
        assertNotNull(bookkeeping.liveLocationCancel)
        job.cancel()
    }

    @Test
    fun readReceiptDefaultsEmpty() {
        val bookkeeping = ChatDetailReadReceiptBookkeeping()
        assertTrue(bookkeeping.readSeenMessages.isEmpty())
        assertNull(bookkeeping.pendingReadWatermarkMessageId)
        assertNull(bookkeeping.lastMessagesSeen)
        assertNull(bookkeeping.markReadJob)
    }

    @Test
    fun readReceiptTracksSeenAndWatermark() {
        val bookkeeping = ChatDetailReadReceiptBookkeeping()
        bookkeeping.readSeenMessages.add("m1")
        bookkeeping.readSeenMessages.add("m2")
        assertTrue(bookkeeping.readSeenMessages.contains("m1"))
        assertEquals(setOf("m1", "m2"), bookkeeping.readSeenMessages.snapshot())
        bookkeeping.pendingReadWatermarkMessageId = "m2"
        bookkeeping.lastMessagesSeen = listOf("m1" to MessageStatus.READ)
        assertEquals("m2", bookkeeping.pendingReadWatermarkMessageId)
        assertEquals(listOf("m1" to MessageStatus.READ), bookkeeping.lastMessagesSeen)
        bookkeeping.readSeenMessages.clearAll()
        assertTrue(bookkeeping.readSeenMessages.isEmpty())
    }

    @Test
    fun readReceiptHoldsMarkReadJob() {
        val bookkeeping = ChatDetailReadReceiptBookkeeping()
        val job = Job()
        bookkeeping.markReadJob = job
        assertEquals(job, bookkeeping.markReadJob)
        job.cancel()
    }

    @Test
    fun aiDefaultsEmpty() {
        val bookkeeping = ChatDetailAiBookkeeping()
        assertNull(bookkeeping.pendingAiAction)
        assertNull(bookkeeping.pendingAiOperationRetryId)
        assertEquals(false, bookkeeping.aiSettingsLoaded)
        assertNull(bookkeeping.unreadSummaryAttemptedForKey)
        assertNull(bookkeeping.unreadSummaryInFlightKey)
        assertTrue(bookkeeping.aiOperationJobs.isEmpty())
        assertTrue(bookkeeping.aiAutoRetryJobs.isEmpty())
        assertTrue(bookkeeping.aiAutoRetryAt.isEmpty())
        assertNotNull(bookkeeping.aiOperationQueueMutex)
        assertNotNull(bookkeeping.aiOperationJson)
        assertNull(bookkeeping.aiRewriteStreamJob)
        assertNull(bookkeeping.aiReplyStreamJob)
        assertNull(bookkeeping.semanticSearchJob)
        assertNull(bookkeeping.groupAiJob)
        assertNull(bookkeeping.manualSummaryJob)
        assertNull(bookkeeping.unreadSummaryJob)
        assertEquals("polish", bookkeeping.lastAiRewriteMode)
        assertNull(bookkeeping.lastAiRewriteTargetLanguage)
        assertEquals("friendly", bookkeeping.lastAiReplyTone)
    }

    @Test
    fun draftDefaultsEmpty() {
        val bookkeeping = ChatDetailDraftBookkeeping()
        assertNull(bookkeeping.draftSaveJob)
        assertEquals(0L, bookkeeping.draftGeneration)
    }
}
