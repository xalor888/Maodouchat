package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissedCallTimeoutPolicyFuzzTest {

    private val random = Random(2026_11_22L)

    private val callIds = listOf("", " ", "  ", "\t", "sig-1", "call-42", "  sig-7  ", "a", "mc_x_1", "u9")
    private val peers = listOf("", " ", "  u9  ", "u9", "a", "user-1", "u 9", "unknown", "  ")
    private val wireStates = listOf(
        "RINGING", "ringing", "Ringing", "rInGiNg", "RINGING ", " RINGING",
        "CONNECTED", "CALLING", "ENDED", "", "ring", "RING", "RINGINGX", "busy", "rejected",
    )

    // 分钟桶边界 ±1ms；负值钉住 Kotlin 整数除法向零截断（-1ms 落 0 号桶）。
    private val nowValues = listOf(
        0L, 1L, 59_999L, 60_000L, 60_001L, 119_999L, 120_000L,
        -1L, -59_999L, -60_000L, -60_001L, 1_000_000_000_000L, 1_000_000_000_001L,
    )

    private fun randomNowMs(): Long =
        if (random.nextInt(4) == 0) nowValues[random.nextInt(nowValues.size)]
        else random.nextLong(-7L * 86_400_000L, 2_000_000_000_000L)

    // receivedAtMillis 只取固定值：data 类相等包含它，随机时间戳会冲掉字段相等组合。
    private fun randomPending(): PendingIncomingCall = PendingIncomingCall(
        contactId = listOf("u1", "u2", "a", "")[random.nextInt(4)],
        contactName = listOf("Alice", "Bob", "")[random.nextInt(3)],
        callType = if (random.nextBoolean()) CallType.AUDIO else CallType.VIDEO,
        offerSdp = listOf("v=0", "v=1")[random.nextInt(2)],
        callId = listOf("", "c1", "c2", "a", "b", " ")[random.nextInt(6)],
        receivedAtMillis = listOf(0L, 1000L, 2000L)[random.nextInt(3)],
    )

    private fun randomPendingPair(): Pair<PendingIncomingCall?, PendingIncomingCall?> {
        val observed = if (random.nextInt(5) == 0) null else randomPending()
        val still = when {
            observed == null -> if (random.nextBoolean()) null else randomPending()
            random.nextInt(4) == 0 -> observed
            random.nextInt(3) == 0 -> observed.copy(
                contactName = listOf("Alice", "Alicia", "Bob")[random.nextInt(3)],
                receivedAtMillis = listOf(0L, 1000L, 2000L)[random.nextInt(3)],
            )
            else -> randomPending()
        }
        return observed to still
    }

    private fun oracleMissedRecordId(signalingCallId: String, fromUserId: String, nowMs: Long): String {
        val callId = signalingCallId.trim()
        if (callId.isNotEmpty()) return callId
        val peer = fromUserId.trim().ifEmpty { "unknown" }
        return "mc_${peer}_${nowMs / 60_000L}"
    }

    private fun oracleShouldRecordMissed(
        observedPending: PendingIncomingCall?,
        stillPending: PendingIncomingCall?,
    ): Boolean {
        if (observedPending == null || stillPending == null) return false
        return stillPending == observedPending ||
            (stillPending.callId.isNotBlank() &&
                stillPending.callId == observedPending.callId &&
                stillPending.contactId == observedPending.contactId)
    }

    private fun oracleShouldShowTray(notificationsEnabled: Boolean): Boolean = notificationsEnabled

    private fun oracleShouldRecordPeerCancelAsMissed(isIncoming: Boolean, callStateWire: String): Boolean {
        if (!isIncoming) return false
        return callStateWire.equals("RINGING", ignoreCase = true)
    }

    @Test
    fun `missedRecordId matches verbatim oracle over random inputs`() {
        repeat(200) {
            val callId = callIds[random.nextInt(callIds.size)]
            val peer = peers[random.nextInt(peers.size)]
            val nowMs = randomNowMs()
            assertEquals(
                oracleMissedRecordId(callId, peer, nowMs),
                MissedCallTimeoutPolicy.missedRecordId(callId, peer, nowMs),
                "callId=$callId peer=$peer nowMs=$nowMs",
            )
        }
    }

    @Test
    fun `shouldRecordMissed matches verbatim oracle over random pending pairs`() {
        repeat(200) {
            val (observed, still) = randomPendingPair()
            assertEquals(
                oracleShouldRecordMissed(observed, still),
                MissedCallTimeoutPolicy.shouldRecordMissed(observed, still),
                "observed=$observed still=$still",
            )
        }
    }

    @Test
    fun `shouldShowTray matches oracle over random booleans`() {
        repeat(200) {
            val enabled = random.nextBoolean()
            assertEquals(oracleShouldShowTray(enabled), MissedCallTimeoutPolicy.shouldShowTray(enabled))
        }
    }

    @Test
    fun `shouldRecordPeerCancelAsMissed matches oracle over random incoming and state`() {
        repeat(200) {
            val incoming = random.nextBoolean()
            val state = if (random.nextInt(4) == 0) {
                "ringing".map { if (random.nextBoolean()) it.uppercaseChar() else it }.joinToString("")
            } else {
                wireStates[random.nextInt(wireStates.size)]
            }
            assertEquals(
                oracleShouldRecordPeerCancelAsMissed(incoming, state),
                MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(incoming, state),
                "incoming=$incoming state=$state",
            )
        }
    }

    @Test
    fun `missedRecordId is stable within one minute bucket`() {
        repeat(200) {
            val bucket = random.nextLong(0L, 10_000L)
            val first = bucket * 60_000L + random.nextLong(0L, 60_000L)
            val second = bucket * 60_000L + random.nextLong(0L, 60_000L)
            assertEquals(
                MissedCallTimeoutPolicy.missedRecordId(" ", "u9", first),
                MissedCallTimeoutPolicy.missedRecordId(" ", "u9", second),
                "bucket=$bucket first=$first second=$second",
            )
        }
    }

    @Test
    fun `missedRecordId changes across minute buckets for the same peer`() {
        repeat(200) {
            val firstBucket = random.nextLong(0L, 10_000L)
            val secondBucket = firstBucket + 1L + random.nextLong(0L, 100L)
            val first = MissedCallTimeoutPolicy.missedRecordId(
                " ", "u9", firstBucket * 60_000L + random.nextLong(0L, 60_000L),
            )
            val second = MissedCallTimeoutPolicy.missedRecordId(
                " ", "u9", secondBucket * 60_000L + random.nextLong(0L, 60_000L),
            )
            assertTrue(first != second, "first=$first second=$second")
        }
    }

    @Test
    fun `missedRecordId ignores peer and time when callId is non-blank`() {
        repeat(200) {
            val callId = listOf("sig-1", "  sig-7  ", "a", "mc_x_1")[random.nextInt(4)]
            val expected = callId.trim()
            assertEquals(expected, MissedCallTimeoutPolicy.missedRecordId(callId, peers[random.nextInt(peers.size)], randomNowMs()))
            assertEquals(expected, MissedCallTimeoutPolicy.missedRecordId(callId, peers[random.nextInt(peers.size)], randomNowMs()))
        }
    }

    @Test
    fun `shouldRecordMissed null combinations never record`() {
        repeat(200) {
            val observed = if (random.nextBoolean()) null else randomPending()
            val still = if (random.nextBoolean()) null else randomPending()
            if (observed == null || still == null) {
                assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(observed, still))
            }
        }
    }

    @Test
    fun `shouldRecordMissed same instance always records even with blank callId`() {
        repeat(200) {
            val pending = randomPending()
            assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(pending, pending))
        }
    }

    @Test
    fun `shouldRecordPeerCancelAsMissed accepts every case variant of ringing`() {
        repeat(200) {
            val state = "ringing".map { if (random.nextBoolean()) it.uppercaseChar() else it }.joinToString("")
            assertTrue(
                MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(isIncoming = true, callStateWire = state),
                "state=$state",
            )
            assertFalse(
                MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(isIncoming = false, callStateWire = state),
                "state=$state",
            )
        }
    }

    @Test
    fun `missedRecordId minute bucket boundaries are pinned`() {
        val cases = listOf(
            Triple(" ", "u9", 0L) to "mc_u9_0",
            Triple(" ", "u9", 59_999L) to "mc_u9_0",
            Triple(" ", "u9", 60_000L) to "mc_u9_1",
            Triple(" ", "u9", 119_999L) to "mc_u9_1",
            Triple(" ", "u9", 120_000L) to "mc_u9_2",
            Triple(" ", "u9", -1L) to "mc_u9_0",
            Triple(" ", "u9", -60_000L) to "mc_u9_-1",
            Triple(" ", "u9", -60_001L) to "mc_u9_-1",
            Triple("", "u9", 61_000L) to "mc_u9_1",
            Triple("  ", "  u9  ", 61_000L) to "mc_u9_1",
            Triple(" ", "  ", 61_000L) to "mc_unknown_1",
            Triple(" ", "", 61_000L) to "mc_unknown_1",
            Triple(" ", "u 9", 0L) to "mc_u 9_0",
        )
        for ((input, expected) in cases) {
            val (callId, peer, nowMs) = input
            assertEquals(expected, MissedCallTimeoutPolicy.missedRecordId(callId, peer, nowMs), "input=$input")
        }
    }

    @Test
    fun `missedRecordId trims callId and peer but keeps inner spaces`() {
        assertEquals("sig-7", MissedCallTimeoutPolicy.missedRecordId("  sig-7  ", "u1", 99L))
        assertEquals("mc_u 9_0", MissedCallTimeoutPolicy.missedRecordId(" ", "u 9", 0L))
        assertEquals("mc_unknown_1", MissedCallTimeoutPolicy.missedRecordId(" ", "unknown", 61_000L))
        assertEquals(
            MissedCallTimeoutPolicy.missedRecordId(" ", "  ", 61_000L),
            MissedCallTimeoutPolicy.missedRecordId(" ", "unknown", 61_000L),
        )
    }

    @Test
    fun `missedRecordId returns non-blank callId verbatim`() {
        assertEquals("mc_x_1", MissedCallTimeoutPolicy.missedRecordId("mc_x_1", "u1", 99L))
        assertEquals("a", MissedCallTimeoutPolicy.missedRecordId("a", "u1", 99L))
        assertEquals("sig-1", MissedCallTimeoutPolicy.missedRecordId("\tsig-1\t", "u1", 99L))
    }

    @Test
    fun `shouldRecordMissed pinned matrix covers null same and identity cases`() {
        fun pending(callId: String = "c1", contactId: String = "u1", name: String = "Alice") =
            PendingIncomingCall(
                contactId = contactId, contactName = name, callType = CallType.AUDIO,
                offerSdp = "v=0", callId = callId, receivedAtMillis = 1000L,
            )
        val p = pending()
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(null, null))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(null, p))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(p, null))
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(p, p))
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(p, pending()))
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(p, pending(name = "Alicia")))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(p, pending(callId = "c2")))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(p, pending(contactId = "u2")))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(p, pending(callId = "c2", contactId = "u2")))
    }

    @Test
    fun `shouldRecordMissed blank callIds never match by identity across instances`() {
        fun pending(callId: String, name: String = "Alice") = PendingIncomingCall(
            contactId = "u1", contactName = name, callType = CallType.AUDIO,
            offerSdp = "v=0", callId = callId, receivedAtMillis = 1000L,
        )
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(pending("c1"), pending("")))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(pending(""), pending("c1")))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(pending(""), pending("", name = "Bob")))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(pending(" "), pending("  ", name = "Bob")))
    }

    @Test
    fun `shouldRecordMissed equal by fields despite different name sdp and timestamp`() {
        val a = PendingIncomingCall(
            contactId = "u1", contactName = "Alice", callType = CallType.AUDIO,
            offerSdp = "v=0", callId = "c1", receivedAtMillis = 1000L,
        )
        val b = a.copy(contactName = "Alicia", offerSdp = "v=1", receivedAtMillis = 2000L)
        assertFalse(a == b)
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(a, b))
    }

    @Test
    fun `shouldRecordMissed same instance with blank callId still records`() {
        val p = PendingIncomingCall(
            contactId = "u1", contactName = "Alice", callType = CallType.AUDIO,
            offerSdp = "v=0", callId = "", receivedAtMillis = 1000L,
        )
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(p, p))
    }

    @Test
    fun `shouldShowTray is a straight boolean passthrough`() {
        assertTrue(MissedCallTimeoutPolicy.shouldShowTray(true))
        assertFalse(MissedCallTimeoutPolicy.shouldShowTray(false))
    }

    @Test
    fun `shouldRecordPeerCancelAsMissed pinned incoming by state matrix`() {
        for (incoming in listOf(true, false)) {
            for (state in listOf("RINGING", "ringing", "Ringing", "rInGiNg")) {
                assertEquals(incoming, MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(incoming, state))
            }
            for (state in listOf("CONNECTED", "CALLING", "ENDED", "", "ring", "RING", "RINGINGX", "busy")) {
                assertFalse(MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(incoming, state), "state=$state")
            }
        }
    }

    @Test
    fun `shouldRecordPeerCancelAsMissed wire state is not trimmed`() {
        assertFalse(MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(true, "RINGING "))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(true, " RINGING"))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(true, "RINGING\n"))
    }

    @Test
    fun `missedRecordId synthetic id keeps mc prefix peer and bucket segments`() {
        repeat(200) {
            val peer = listOf("u9", "a", "user-1", "u 9")[random.nextInt(4)]
            val nowMs = randomNowMs()
            val id = MissedCallTimeoutPolicy.missedRecordId(" ", peer, nowMs)
            assertTrue(id.startsWith("mc_"), "id=$id")
            assertTrue(id.endsWith("_${nowMs / 60_000L}"), "id=$id nowMs=$nowMs")
        }
    }

    @Test
    fun `both functions are deterministic for the same inputs`() {
        repeat(200) {
            val callId = callIds[random.nextInt(callIds.size)]
            val peer = peers[random.nextInt(peers.size)]
            val nowMs = randomNowMs()
            assertEquals(
                MissedCallTimeoutPolicy.missedRecordId(callId, peer, nowMs),
                MissedCallTimeoutPolicy.missedRecordId(callId, peer, nowMs),
            )
            val (observed, still) = randomPendingPair()
            assertEquals(
                MissedCallTimeoutPolicy.shouldRecordMissed(observed, still),
                MissedCallTimeoutPolicy.shouldRecordMissed(observed, still),
            )
        }
    }
}
