package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessageRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ScheduledMessageFactoryTest {

    private val nowMs = 1_700_000_000_000L
    private val minSendAt = nowMs + 60_000L
    private val maxSendAt = nowMs + 7L * 24 * 60 * 60 * 1000

    private fun request(
        text: String = "hello",
        sendAtMillis: Long = nowMs + 3_600_000L,
        repeatIntervalMs: Long = 0L,
        repeatCount: Int = 0,
    ) = ScheduledMessageRequest(
        chatId = "c1",
        peerUserId = "u2",
        text = text,
        sendAtMillis = sendAtMillis,
        isGroup = false,
        repeatIntervalMs = repeatIntervalMs,
        repeatCount = repeatCount,
    )

    private fun build(
        request: ScheduledMessageRequest = request(),
        ownerUserId: String = "u1",
        pendingCount: Int = 0,
    ) = ScheduledMessageFactory.build(
        request = request,
        ownerUserId = ownerUserId,
        pendingCount = { pendingCount },
        nowMs = nowMs,
        idSuffix = "abcdefghijkl",
        timeZoneId = "Asia/Shanghai",
        idempotencyKey = "key-1",
    )

    private fun stored() = ScheduledMessage(
        id = "sch_abcdefghijkl",
        chatId = "c1",
        peerUserId = "u2",
        text = "old",
        sendAtMillis = nowMs + 3_600_000L,
        createdAtMillis = nowMs,
        ownerUserId = "u1",
        timeZoneId = "Asia/Shanghai",
    )

    @Test
    fun buildBlankOwnerReturnsNull() {
        assertNull(build(ownerUserId = "   "))
    }

    @Test
    fun buildBlankChatReturnsNull() {
        assertNull(build(request = request().copy(chatId = "")))
    }

    @Test
    fun buildBlankTextReturnsNull() {
        assertNull(build(request = request(text = "   ")))
    }

    @Test
    fun buildTruncatesOverlongTextTo4000() {
        val built = build(request = request(text = "x".repeat(4001)))
        assertNotNull(built)
        assertEquals(4000, built.text.length)
    }

    @Test
    fun buildClampsSendAtToWindow() {
        assertEquals(minSendAt, build(request = request(sendAtMillis = nowMs + 1000L))?.sendAtMillis)
        assertEquals(maxSendAt, build(request = request(sendAtMillis = nowMs + 30L * 24 * 60 * 60 * 1000))?.sendAtMillis)
    }

    @Test
    fun buildPendingLimitBoundary() {
        assertNull(build(pendingCount = 56))
        assertNotNull(build(pendingCount = 55))
    }

    @Test
    fun buildIdFormatAndDefaults() {
        val built = assertNotNull(build(request = request(repeatIntervalMs = -5L, repeatCount = -2)))
        assertEquals("sch_abcdefghijkl", built.id)
        assertEquals("PENDING", built.status)
        assertEquals(0, built.occurrencesSent)
        assertEquals(0, built.attempt)
        assertEquals(0L, built.repeatIntervalMs)
        assertEquals(0, built.repeatCount)
        assertEquals("Asia/Shanghai", built.timeZoneId)
        assertEquals("key-1", built.idempotencyKey)
        assertEquals(nowMs, built.createdAtMillis)
        assertEquals("hello", built.text)
    }

    @Test
    fun applyUpdateBlankTextReturnsNull() {
        assertNull(ScheduledMessageFactory.applyUpdate(stored(), "   ", null, nowMs, "UTC"))
    }

    @Test
    fun applyUpdateNullTextKeepsOldAndClampsSendAt() {
        val updated = assertNotNull(
            ScheduledMessageFactory.applyUpdate(stored(), null, nowMs + 1000L, nowMs, "UTC")
        )
        assertEquals("old", updated.text)
        assertEquals(minSendAt, updated.sendAtMillis)
        assertEquals("Asia/Shanghai", updated.timeZoneId)
        assertEquals("sch_abcdefghijkl", updated.id)
    }

    @Test
    fun applyUpdateBlankTimeZoneFallsBack() {
        val updated = assertNotNull(
            ScheduledMessageFactory.applyUpdate(stored().copy(timeZoneId = ""), "new", null, nowMs, "UTC")
        )
        assertEquals("new", updated.text)
        assertEquals("UTC", updated.timeZoneId)
    }
}
