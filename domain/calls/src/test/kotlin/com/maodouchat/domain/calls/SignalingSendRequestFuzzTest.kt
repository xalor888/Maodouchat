package com.maodouchat.domain.calls

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SignalingSendRequestFuzzTest {

    private val random = Random(0xCA11_2026L)
    private val json = Json { ignoreUnknownKeys = true }

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(
            ' ', '_', '-', '.', '/', ':', '"', '\\', '\n', '\t', 'é', '中', '文',
        )
        return (0 until random.nextInt(0, 16)).map { pool.random(random) }.joinToString("")
    }

    private fun randomLong(): Long =
        when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> random.nextLong(-1000, 1000)
            else -> random.nextLong()
        }

    private fun randomRequest(): SignalingSendRequest =
        SignalingSendRequest(
            toUserId = randomString(),
            type = randomString(),
            payload = randomString(),
            callId = randomString(),
            groupId = randomString(),
            groupMemberIds = List(random.nextInt(0, 4)) { randomString() },
            groupInvite = random.nextBoolean(),
            epoch = randomLong(),
            sequence = randomLong(),
            idempotencyKey = randomString(),
        )

    @Test
    fun `json round-trips random requests`() {
        repeat(300) {
            val request = randomRequest()
            val decoded = json.decodeFromString<SignalingSendRequest>(json.encodeToString(request))
            assertEquals(request, decoded, "request=$request")
        }
    }

    @Test
    fun `unknown keys are ignored over random extra fields`() {
        repeat(100) {
            val request = randomRequest()
            val encoded = json.encodeToString(request)
            val extraKey = "extra_${random.nextInt(1000)}"
            val withExtra = encoded.dropLast(1) + ",\"$extraKey\":true,\"another\":123}"
            val decoded = json.decodeFromString<SignalingSendRequest>(withExtra)
            assertEquals(request, decoded, "request=$request")
        }
    }

    @Test
    fun `missing optional fields decode to defaults`() {
        repeat(100) {
            val request = randomRequest()
            val minimal = """{"toUserId":${json.encodeToString(request.toUserId)},"type":${json.encodeToString(request.type)},"payload":${json.encodeToString(request.payload)}}"""
            val decoded = json.decodeFromString<SignalingSendRequest>(minimal)
            assertEquals(request.toUserId, decoded.toUserId)
            assertEquals(request.type, decoded.type)
            assertEquals(request.payload, decoded.payload)
            assertEquals("", decoded.callId)
            assertEquals("", decoded.groupId)
            assertEquals(emptyList<String>(), decoded.groupMemberIds)
            assertEquals(false, decoded.groupInvite)
            assertEquals(0L, decoded.epoch)
            assertEquals(0L, decoded.sequence)
            assertEquals("", decoded.idempotencyKey)
        }
    }
}
