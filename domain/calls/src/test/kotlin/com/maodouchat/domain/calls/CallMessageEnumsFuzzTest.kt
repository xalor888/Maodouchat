package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CallMessageEnumsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomName(pool: List<String>): String {
        val letters = ('a'..'z') + ('A'..'Z') + '_'
        while (true) {
            val name = (1..random.nextInt(1, 12)).map { letters.random(random) }.joinToString("")
            if (name !in pool) return name
        }
    }

    private inline fun <reified E : Enum<E>> assertUnknownNameThrows(pool: List<String>) {
        repeat(20) {
            val bad = randomName(pool)
            assertFailsWith<IllegalArgumentException> { enumValueOf<E>(bad) }
        }
    }

    @Test
    fun `valueOf round-trips every entry of all five message enums`() {
        for (value in CallIncomingCallMessage.entries) assertEquals(value, enumValueOf<CallIncomingCallMessage>(value.name))
        for (value in CallOutgoingCallMessage.entries) assertEquals(value, enumValueOf<CallOutgoingCallMessage>(value.name))
        for (value in CallRingingTimeoutMessage.entries) assertEquals(value, enumValueOf<CallRingingTimeoutMessage>(value.name))
        for (value in CallSignalingIngressMessage.entries) assertEquals(value, enumValueOf<CallSignalingIngressMessage>(value.name))
        for (value in CallSignalingMessage.entries) assertEquals(value, enumValueOf<CallSignalingMessage>(value.name))
    }

    @Test
    fun `entries have distinct ordinals and names`() {
        val allEntries = listOf(
            CallIncomingCallMessage.entries.map { it.name to it.ordinal },
            CallOutgoingCallMessage.entries.map { it.name to it.ordinal },
            CallRingingTimeoutMessage.entries.map { it.name to it.ordinal },
            CallSignalingIngressMessage.entries.map { it.name to it.ordinal },
            CallSignalingMessage.entries.map { it.name to it.ordinal },
        )
        for (entries in allEntries) {
            assertEquals(entries.size, entries.map { it.first }.toSet().size)
            assertEquals(entries.size, entries.map { it.second }.toSet().size)
            assertEquals(entries.indices.toList(), entries.map { it.second }.sorted())
        }
    }

    @Test
    fun `entry order is pinned per message enum`() {
        assertEquals(
            listOf("NOTIFY_BUSY_FAILED", "GROUP_NAME", "INCOMING_INCOMPLETE", "SESSION_EXPIRED", "WEBRTC_DOWNLOAD_FAILED", "ANSWER_FAILED", "UNKNOWN_ERROR", "NOTIFY_REJECT_FAILED"),
            CallIncomingCallMessage.entries.map { it.name },
        )
        assertEquals(
            listOf("CALLS_DISABLED", "VIDEO_CALL_DISABLED", "VOICE_CALL_DISABLED", "SESSION_EXPIRED", "GROUP_CALL", "GROUP_NAME", "GROUP_MESH_LIMIT", "WEBRTC_DOWNLOAD_FAILED", "INITIALIZATION_FAILED", "UNKNOWN_ERROR", "GROUP_START_FAILED"),
            CallOutgoingCallMessage.entries.map { it.name },
        )
        assertEquals(listOf("CALL_NO_ANSWER"), CallRingingTimeoutMessage.entries.map { it.name })
        assertEquals(
            listOf("RATE_LIMITED", "FETCH_SIGNALING_FAILED", "NOTIFY_BUSY_FAILED", "PEER_REJECTED", "PEER_BUSY", "HANDLE_SIGNALING_FAILED", "UNKNOWN_ERROR"),
            CallSignalingIngressMessage.entries.map { it.name },
        )
        assertEquals(
            listOf("SEND_SDP_FAILED", "SEND_CANDIDATE_FAILED", "SESSION_EXPIRED", "ERROR_WITH_REASON", "NETWORK_DISCONNECTED"),
            CallSignalingMessage.entries.map { it.name },
        )
    }

    @Test
    fun `unknown names throw for every message enum`() {
        assertUnknownNameThrows<CallIncomingCallMessage>(CallIncomingCallMessage.entries.map { it.name })
        assertUnknownNameThrows<CallOutgoingCallMessage>(CallOutgoingCallMessage.entries.map { it.name })
        assertUnknownNameThrows<CallRingingTimeoutMessage>(CallRingingTimeoutMessage.entries.map { it.name })
        assertUnknownNameThrows<CallSignalingIngressMessage>(CallSignalingIngressMessage.entries.map { it.name })
        assertUnknownNameThrows<CallSignalingMessage>(CallSignalingMessage.entries.map { it.name })
    }

    @Test
    fun `shared names across message enums are exactly the expected overlaps`() {
        val incoming = CallIncomingCallMessage.entries.map { it.name }.toSet()
        val outgoing = CallOutgoingCallMessage.entries.map { it.name }.toSet()
        val ringing = CallRingingTimeoutMessage.entries.map { it.name }.toSet()
        val ingress = CallSignalingIngressMessage.entries.map { it.name }.toSet()
        val signaling = CallSignalingMessage.entries.map { it.name }.toSet()
        assertEquals(setOf("SESSION_EXPIRED", "GROUP_NAME", "WEBRTC_DOWNLOAD_FAILED", "UNKNOWN_ERROR"), incoming intersect outgoing)
        assertEquals(setOf("NOTIFY_BUSY_FAILED", "UNKNOWN_ERROR"), incoming intersect ingress)
        assertEquals(setOf("UNKNOWN_ERROR"), outgoing intersect ingress)
        assertEquals(setOf("SESSION_EXPIRED"), outgoing intersect signaling)
        assertEquals(setOf("SESSION_EXPIRED"), incoming intersect signaling)
        assertEquals(emptySet(), ringing intersect incoming)
        assertEquals(emptySet(), ringing intersect outgoing)
        assertEquals(emptySet(), ringing intersect ingress)
        assertEquals(emptySet(), ringing intersect signaling)
        assertEquals(emptySet(), ingress intersect signaling)
        assertEquals(25, (incoming + outgoing + ringing + ingress + signaling).size)
    }
}
