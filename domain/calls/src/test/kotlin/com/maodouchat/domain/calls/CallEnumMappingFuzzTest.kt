package com.maodouchat.domain.calls

import kotlin.enums.enumValueOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CallEnumMappingFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomName(pool: List<String>): String {
        val letters = ('a'..'z') + ('A'..'Z') + '_'
        while (true) {
            val name = (1..random.nextInt(1, 12)).map { letters.random(random) }.joinToString("")
            if (name !in pool) return name
        }
    }

    @Test
    fun `groupParticipantStatusKind maps every state by name`() {
        for (state in GroupPeerConnectionState.entries) {
            val kind = groupParticipantStatusKind(state)
            assertEquals(state.name, kind.name, "state=$state")
            assertEquals(state, GroupPeerConnectionState.valueOf(kind.name))
        }
        assertEquals(8, GroupParticipantStatusKind.entries.size)
        assertEquals(
            GroupPeerConnectionState.entries.map { it.name }.toSet(),
            GroupParticipantStatusKind.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `enum valueOf round-trips every entry`() {
        for (value in NetworkQuality.entries) assertEquals(value, enumValueOf<NetworkQuality>(value.name))
        for (value in CallState.entries) assertEquals(value, enumValueOf<CallState>(value.name))
        for (value in Direction.entries) assertEquals(value, enumValueOf<Direction>(value.name))
        for (value in State.entries) assertEquals(value, enumValueOf<State>(value.name))
        for (value in GroupPeerConnectionState.entries) {
            assertEquals(value, enumValueOf<GroupPeerConnectionState>(value.name))
        }
        for (value in CallAudioRoute.entries) assertEquals(value, enumValueOf<CallAudioRoute>(value.name))
        for (value in CallMediaPermission.entries) {
            assertEquals(value, enumValueOf<CallMediaPermission>(value.name))
        }
        for (value in FailureOperation.entries) assertEquals(value, enumValueOf<FailureOperation>(value.name))
        for (value in Feature.entries) assertEquals(value, enumValueOf<Feature>(value.name))
    }

    @Test
    fun `enum entries have distinct ordinals and names`() {
        val allEntries = listOf(
            NetworkQuality.entries.map { it.name to it.ordinal },
            CallState.entries.map { it.name to it.ordinal },
            Direction.entries.map { it.name to it.ordinal },
            State.entries.map { it.name to it.ordinal },
            GroupPeerConnectionState.entries.map { it.name to it.ordinal },
            CallAudioRoute.entries.map { it.name to it.ordinal },
            CallMediaPermission.entries.map { it.name to it.ordinal },
            FailureOperation.entries.map { it.name to it.ordinal },
            Feature.entries.map { it.name to it.ordinal },
        )
        for (entries in allEntries) {
            assertEquals(entries.size, entries.map { it.first }.toSet().size)
            assertEquals(entries.size, entries.map { it.second }.toSet().size)
            assertEquals(entries.indices.toList(), entries.map { it.second }.sorted())
        }
    }

    @Test
    fun `unknown enum names throw`() {
        repeat(100) {
            val badQuality = randomName(NetworkQuality.entries.map { it.name })
            assertFailsWith<IllegalArgumentException> { enumValueOf<NetworkQuality>(badQuality) }
            val badState = randomName(CallState.entries.map { it.name })
            assertFailsWith<IllegalArgumentException> { enumValueOf<CallState>(badState) }
            val badRoute = randomName(CallAudioRoute.entries.map { it.name })
            assertFailsWith<IllegalArgumentException> { enumValueOf<CallAudioRoute>(badRoute) }
        }
    }

    @Test
    fun `network quality and call state entries keep their pinned order`() {
        assertEquals(
            listOf("GOOD", "FAIR", "POOR", "UNKNOWN"),
            NetworkQuality.entries.map { it.name },
        )
        assertEquals(
            listOf("IDLE", "CALLING", "RINGING", "CONNECTED", "DISCONNECTED"),
            CallState.entries.map { it.name },
        )
        assertTrue(NetworkQuality.UNKNOWN.ordinal == NetworkQuality.entries.lastIndex)
    }
}
