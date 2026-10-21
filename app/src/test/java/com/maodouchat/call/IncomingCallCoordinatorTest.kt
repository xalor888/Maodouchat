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
    ) = PendingIncomingCall(
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
        assertTrue(IncomingCallCoordinator.consumedEvents.value >= beforeConsumed)
    }

    @Test
    fun `blank call ids do not fail the identity check`() {
        IncomingCallCoordinator.setPending(pending(callId = ""))
        assertTrue(IncomingCallCoordinator.markAutoAnswer("call-1"))
        assertTrue(IncomingCallCoordinator.peekPending()?.autoAnswer == true)
    }

    @Test
    fun `consumePending returns fresh pending and stamps consumedEvents`() {
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value
        val call = pending()
        IncomingCallCoordinator.setPending(call)

        val consumed = IncomingCallCoordinator.consumePending()

        assertSame(call, consumed)
        assertNull(IncomingCallCoordinator.peekPending())
        assertTrue(IncomingCallCoordinator.consumedEvents.value >= beforeConsumed)
    }

    @Test
    fun `consumePending on stale pending returns null but clears and stamps`() {
        IncomingCallCoordinator.setPending(
            pending(receivedAtMillis = System.currentTimeMillis() - IncomingCallCoordinator.STALE_MS - 1L),
        )
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value

        assertNull(IncomingCallCoordinator.consumePending())
        assertNull(IncomingCallCoordinator.peekPending())
        assertTrue(IncomingCallCoordinator.consumedEvents.value >= beforeConsumed)
    }

    @Test
    fun `clear on empty pending leaves consumedEvents untouched`() {
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value

        IncomingCallCoordinator.clear()

        assertNull(IncomingCallCoordinator.peekPending())
        assertEquals(beforeConsumed, IncomingCallCoordinator.consumedEvents.value)
    }

    @Test
    fun `setPending replaces previous pending without stamping consumedEvents`() {
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value
        IncomingCallCoordinator.setPending(pending(callId = "call-1"))
        IncomingCallCoordinator.setPending(pending(callId = "call-2"))

        assertEquals("call-2", IncomingCallCoordinator.peekPending()?.callId)
        assertEquals(beforeConsumed, IncomingCallCoordinator.consumedEvents.value)
    }

    @Test
    fun `markAutoAnswer with blank arg bypasses identity check on non-blank pending`() {
        IncomingCallCoordinator.setPending(pending(callId = "call-1"))

        assertTrue(IncomingCallCoordinator.markAutoAnswer(""))
        assertTrue(IncomingCallCoordinator.peekPending()?.autoAnswer == true)
    }

    @Test
    fun `消费后再次消费返回 null 且不重复记 consumedEvents`() {
        // 消费是原子取清：第二次消费无残留，也不重复打戳。
        val call = pending()
        IncomingCallCoordinator.setPending(call)

        assertSame(call, IncomingCallCoordinator.consumePending())
        val afterFirst = IncomingCallCoordinator.consumedEvents.value

        assertNull(IncomingCallCoordinator.consumePending())
        assertEquals(afterFirst, IncomingCallCoordinator.consumedEvents.value)
    }

    @Test
    fun `无 pending 时 markAutoAnswer 返回 false 且不记 consumedEvents`() {
        // 空通道上标记接听是误操作：直接失败，不污染事件流。
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value

        assertFalse(IncomingCallCoordinator.markAutoAnswer("call-1"))
        assertEquals(beforeConsumed, IncomingCallCoordinator.consumedEvents.value)
    }

    @Test
    fun `clear 清掉有效 pending 并记 consumedEvents`() {
        // 挂断就是取消：clear 清通道的同时打戳，让超时检测侧可感知。
        IncomingCallCoordinator.setPending(pending())
        val beforeConsumed = IncomingCallCoordinator.consumedEvents.value

        IncomingCallCoordinator.clear()

        assertNull(IncomingCallCoordinator.peekPending())
        assertTrue(IncomingCallCoordinator.consumedEvents.value >= beforeConsumed)
    }

    @Test
    fun `重复 markAutoAnswer 只产生一个新实例`() {
        // 第二次标记命中 autoAnswer 短路，不再 copy：实例稳定。
        val original = pending()
        IncomingCallCoordinator.setPending(original)

        assertTrue(IncomingCallCoordinator.markAutoAnswer("call-1"))
        val marked = IncomingCallCoordinator.peekPending()
        requireNotNull(marked)
        assertTrue(IncomingCallCoordinator.markAutoAnswer("call-1"))
        assertSame(marked, IncomingCallCoordinator.peekPending())
    }

    @Test
    fun `切换账号后的新来电覆盖旧 pending`() {
        // 换账号后来电归属不同联系人：新 pending 直接替换，不叠加。
        IncomingCallCoordinator.setPending(pending(callId = "call-1"))
        IncomingCallCoordinator.setPending(
            PendingIncomingCall(
                contactId = "user-b",
                contactName = "Bob",
                callType = CallType.AUDIO,
                offerSdp = "v=0",
                callId = "call-2",
            ),
        )

        val current = IncomingCallCoordinator.peekPending()
        requireNotNull(current)
        assertEquals("call-2", current.callId)
        assertEquals("user-b", current.contactId)
    }
}
