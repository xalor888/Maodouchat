package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallCoordinatorTest {

    @After
    fun tearDown() {
        IncomingCallCoordinator.clear()
    }

    private fun pending(
        callId: String = "call-1",
        autoAnswer: Boolean = false,
        receivedAtMillis: Long = System.currentTimeMillis(),
    ) = IncomingCallCoordinator.PendingIncomingCall(
        contactId = "user-a",
        contactName = "Alice",
        callType = CallType.AUDIO,
        offerSdp = "v=0",
        callId = callId,
        receivedAtMillis = receivedAtMillis,
        autoAnswer = autoAnswer,
    )

    @Test
    fun `markAutoAnswer copies pending so peek returns a new autoAnswer instance`() {
        val original = pending()
        IncomingCallCoordinator.setPending(original)

        assertTrue(IncomingCallCoordinator.markAutoAnswer("call-1"))

        val marked = IncomingCallCoordinator.peekPending()
        requireNotNull(marked)
        assertTrue(marked.autoAnswer)
        assertEquals("call-1", marked.callId)
        assertNotSame(original, marked)
        assertFalse(original.autoAnswer)
    }

    @Test
    fun `markAutoAnswer rejects a different call id without clearing`() {
        IncomingCallCoordinator.setPending(pending(callId = "call-1"))

        assertFalse(IncomingCallCoordinator.markAutoAnswer("call-other"))

        val still = IncomingCallCoordinator.peekPending()
        requireNotNull(still)
        assertEquals("call-1", still.callId)
        assertFalse(still.autoAnswer)
    }

    @Test
    fun `markAutoAnswer is idempotent when already autoAnswer`() {
        val already = pending(autoAnswer = true)
        IncomingCallCoordinator.setPending(already)

        assertTrue(IncomingCallCoordinator.markAutoAnswer("call-1"))
        assertSame(already, IncomingCallCoordinator.peekPending())
    }

    @Test
    fun `markAutoAnswer on stale pending clears and returns false`() {
        IncomingCallCoordinator.setPending(
            pending(receivedAtMillis = System.currentTimeMillis() - IncomingCallCoordinator.STALE_MS - 1L),
        )
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value

        assertFalse(IncomingCallCoordinator.markAutoAnswer("call-1"))
        assertNull(IncomingCallCoordinator.peekPending())
        assertTrue(IncomingCallCoordinator.consumedEvents.value >= beforeConsumed)
    }

    @Test
    fun `peekPending of stale offer clears consumedEvents`() {
        IncomingCallCoordinator.setPending(
            pending(receivedAtMillis = System.currentTimeMillis() - IncomingCallCoordinator.STALE_MS - 5_000L),
        )
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value

        assertNull(IncomingCallCoordinator.peekPending())
        assertTrue(IncomingCallCoordinator.consumedEvents.value > beforeConsumed)
    }

    @Test
    fun `blank call ids do not fail the identity check`() {
        IncomingCallCoordinator.setPending(pending(callId = ""))
        assertTrue(IncomingCallCoordinator.markAutoAnswer("call-1"))
        assertTrue(IncomingCallCoordinator.peekPending()?.autoAnswer == true)
    }
}
