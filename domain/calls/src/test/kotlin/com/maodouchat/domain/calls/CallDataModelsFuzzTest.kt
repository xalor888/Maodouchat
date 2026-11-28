package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CallDataModelsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(
            ' ', '_', '-', '.', 'é', '中', '文',
        )
        return (0 until random.nextInt(0, 12)).map { pool.random(random) }.joinToString("")
    }

    private fun randomLong(): Long =
        when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> random.nextLong(-1000, 1000)
            else -> random.nextLong()
        }

    private fun randomLogEntry(): CallLogEntry =
        CallLogEntry(
            id = randomString(),
            peerId = randomString(),
            peerName = randomString(),
            isVideo = random.nextBoolean(),
            direction = Direction.entries.random(random),
            state = State.entries.random(random),
            startedAt = randomLong(),
            durationMs = randomLong(),
            isGroup = random.nextBoolean(),
        )

    private fun randomLogRow(): CallLogRow =
        CallLogRow(
            id = randomString(),
            peerId = randomString(),
            peerName = randomString(),
            video = random.nextBoolean(),
            direction = Direction.entries.random(random),
            state = State.entries.random(random),
            at = randomLong(),
            durationMs = randomLong(),
        )

    private fun randomPendingCall(): PendingIncomingCall =
        PendingIncomingCall(
            contactId = randomString(),
            contactName = randomString(),
            callType = CallType.entries.random(random),
            offerSdp = randomString(),
            callId = randomString(),
            groupId = randomString(),
            groupMemberIds = List(random.nextInt(0, 4)) { randomString() },
            receivedAtMillis = randomLong(),
            autoAnswer = random.nextBoolean(),
        )

    private fun randomUiState(): CallUiState =
        CallUiState(
            contactId = randomString(),
            contactName = randomString(),
            contactAvatar = if (random.nextBoolean()) null else randomString(),
            callType = CallType.entries.random(random),
            callState = CallState.entries.random(random),
            isIncoming = random.nextBoolean(),
            isGroupCall = random.nextBoolean(),
            duration = randomString(),
            isInitializing = random.nextBoolean(),
            networkReconnecting = random.nextBoolean(),
            networkStats = NetworkQuality.entries.random(random),
            iceStunOnly = random.nextBoolean(),
            availableAudioRoutes = CallAudioRoute.entries.filter { random.nextBoolean() }.toSet(),
            selectedAudioRoute = if (random.nextBoolean()) null else CallAudioRoute.entries.random(random),
            groupParticipants = List(random.nextInt(0, 3)) {
                GroupCallParticipantUi(
                    userId = randomString(),
                    name = randomString(),
                    avatar = if (random.nextBoolean()) null else randomString(),
                    connectionState = GroupPeerConnectionState.entries.random(random),
                    videoAvailable = random.nextBoolean(),
                )
            },
            errorMessage = if (random.nextBoolean()) null else randomString(),
            nativeDownloadProgress = random.nextInt(-10, 110),
        )

    @Test
    fun `call log entry copy and equality over random fields`() {
        repeat(200) {
            val entry = randomLogEntry()
            assertEquals(entry, entry.copy())
            assertEquals(entry.hashCode(), entry.copy().hashCode())
            assertEquals(entry, entry.copy(durationMs = entry.durationMs))
            assertNotEquals(entry, entry.copy(durationMs = entry.durationMs + 1))
            assertNotEquals(entry, entry.copy(id = entry.id + "x"))
            assertNotEquals(entry, entry.copy(peerId = entry.peerId + "x"))
        }
    }

    @Test
    fun `call log row copy and equality over random fields`() {
        repeat(200) {
            val row = randomLogRow()
            assertEquals(row, row.copy())
            assertEquals(row.hashCode(), row.copy().hashCode())
            assertNotEquals(row, row.copy(at = row.at + 1))
            assertNotEquals(row, row.copy(durationMs = row.durationMs + 1))
            assertNotEquals(row, row.copy(video = !row.video))
        }
    }

    @Test
    fun `pending incoming call keeps explicit fields and defaults`() {
        repeat(200) {
            val call = randomPendingCall()
            assertEquals(call, call.copy())
            assertNotEquals(call, call.copy(callId = call.callId + "x"))
            assertNotEquals(call, call.copy(autoAnswer = !call.autoAnswer))
        }
        val minimal = PendingIncomingCall(
            contactId = "u1", contactName = "A", callType = CallType.AUDIO, offerSdp = "sdp",
        )
        assertEquals("", minimal.callId)
        assertEquals("", minimal.groupId)
        assertEquals(emptyList<String>(), minimal.groupMemberIds)
        assertEquals(false, minimal.autoAnswer)
        val before = System.currentTimeMillis()
        val fresh = PendingIncomingCall(
            contactId = "u1", contactName = "A", callType = CallType.AUDIO, offerSdp = "sdp",
        )
        val after = System.currentTimeMillis()
        assertTrue(fresh.receivedAtMillis in before..after)
    }

    @Test
    fun `call ui state defaults and copy over random fields`() {
        val defaults = CallUiState()
        assertEquals("", defaults.contactId)
        assertEquals("", defaults.contactName)
        assertEquals(null, defaults.contactAvatar)
        assertEquals(CallType.AUDIO, defaults.callType)
        assertEquals(CallState.IDLE, defaults.callState)
        assertEquals(false, defaults.isIncoming)
        assertEquals("00:00", defaults.duration)
        assertEquals(NetworkQuality.UNKNOWN, defaults.networkStats)
        assertEquals(emptySet<CallAudioRoute>(), defaults.availableAudioRoutes)
        assertEquals(null, defaults.selectedAudioRoute)
        assertEquals(emptyList<GroupCallParticipantUi>(), defaults.groupParticipants)
        assertEquals(0, defaults.nativeDownloadProgress)
        repeat(200) {
            val state = randomUiState()
            assertEquals(state, state.copy())
            assertEquals(state.hashCode(), state.copy().hashCode())
            assertNotEquals(state, state.copy(nativeDownloadProgress = state.nativeDownloadProgress + 1))
            assertNotEquals(state, state.copy(duration = state.duration + "x"))
        }
    }

    @Test
    fun `group participant ui name defaults to user id`() {
        repeat(200) {
            val userId = randomString()
            val participant = GroupCallParticipantUi(userId = userId)
            assertEquals(userId, participant.name)
            assertEquals(null, participant.avatar)
            assertEquals(GroupPeerConnectionState.CONNECTING, participant.connectionState)
            assertEquals(false, participant.videoAvailable)
            // randomString() 可能是空串；copy 后的 name 必须与默认值（= userId）不同。
            val named = participant.copy(name = if (userId.isEmpty()) "x" else "x$userId")
            assertNotEquals(participant, named)
        }
    }
}
