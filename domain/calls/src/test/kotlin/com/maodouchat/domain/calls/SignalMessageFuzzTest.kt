package com.maodouchat.domain.calls

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalMessageFuzzTest {

    private val random = Random(0xCA11_2026L)
    private val json = Json { ignoreUnknownKeys = true }

    private val types = listOf("offer", "answer", "ice-candidate", "hang-up", "bye", "", "OFFER")

    private fun randomString(): String =
        when (random.nextInt(8)) {
            0 -> ""
            1 -> "plain-text"
            2 -> "with \"quotes\" and \\backslashes\\"
            3 -> "line1\nline2\ttabbed"
            4 -> "unicode \u00e9\u4e2d\u6587\ud83d\ude00"
            5 -> "{\"sdp\":\"v=0\",\"mid\":0}"
            6 -> "  padded  "
            else -> (1..random.nextInt(1, 24)).map {
                ('a'..'z').random(random)
            }.joinToString("")
        }

    private fun randomLong(): Long =
        when (random.nextInt(6)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> -1L
            4 -> random.nextLong(0, 1_000_000_000)
            else -> random.nextLong()
        }

    private fun randomMessage(): SignalMessage = SignalMessage(
        id = randomString(),
        fromUserId = randomString(),
        type = types.random(random),
        payload = randomString(),
        timestamp = randomLong(),
        callId = randomString(),
        groupId = randomString(),
        groupMemberIds = List(random.nextInt(0, 5)) { randomString() },
        groupInvite = random.nextBoolean(),
        epoch = randomLong(),
        sequence = randomLong(),
        idempotencyKey = randomString(),
    )

    @Test
    fun `random signal messages round-trip`() {
        repeat(300) {
            val message = randomMessage()
            assertEquals(message, json.decodeFromString<SignalMessage>(json.encodeToString(message)))
        }
    }

    @Test
    fun `missing optional fields decode to defaults`() {
        repeat(100) {
            val decoded = json.decodeFromString<SignalMessage>(
                """{"id":"sig-1","fromUserId":"u1","type":"offer","payload":"{}","timestamp":123}""",
            )
            assertEquals("", decoded.callId)
            assertEquals("", decoded.groupId)
            assertEquals(emptyList<String>(), decoded.groupMemberIds)
            assertFalse(decoded.groupInvite)
            assertEquals(0L, decoded.epoch)
            assertEquals(0L, decoded.sequence)
            assertEquals("", decoded.idempotencyKey)
            assertEquals(123L, decoded.timestamp)
        }
    }

    @Test
    fun `unknown keys are ignored`() {
        repeat(100) {
            val message = randomMessage()
            val encoded = json.encodeToString(message)
            val withUnknown = encoded.dropLast(1) + ""","retransmit":true,"future":{"nested":1},"extra":null}"""
            assertEquals(message, json.decodeFromString<SignalMessage>(withUnknown))
        }
    }

    @Test
    fun `encode is stable across repeated encoding`() {
        repeat(200) {
            val message = randomMessage()
            val first = json.encodeToString(message)
            val second = json.encodeToString(json.decodeFromString<SignalMessage>(first))
            assertEquals(first, second)
            assertTrue(first.contains("\"type\":\"${message.type}\""))
        }
    }
}
