package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 信令数据模型（SignalMessage / SignalingSendRequest）的随机字段差分 fuzz。
 * JSON 往返已在 #2060/#2061 覆盖，本文件只补 copy/equals/hashCode 不变量
 * 与默认值钉住；另钉住两个原生异常的文案保留。随机输入固定种子，可复现。
 */
class SignalModelsDifferentialFuzzTest {

    private val random = Random(20261122)

    private fun randomString(): String {
        val fragments = listOf(
            "", "a", "user-1", "call-xyz", "a b", "a\"b", "a\\b", "a\nb", "a\tb",
            "üñïçødé", "😀", "x".repeat(200), "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "stun:stun.l.google.com:19302", "2026-11-22"
        )
        return when (random.nextInt(0, 3)) {
            0 -> fragments[random.nextInt(fragments.size)]
            1 -> (1..random.nextInt(0, 13)).map { ('a'..'z').random(random) }.joinToString("")
            else -> fragments[random.nextInt(fragments.size)] + random.nextInt(0, 1000).toString()
        }
    }

    private fun randomLong(): Long = when (random.nextInt(0, 5)) {
        0 -> 0L
        1 -> Long.MIN_VALUE
        2 -> Long.MAX_VALUE
        3 -> random.nextLong(-1000, 1000)
        else -> random.nextLong()
    }

    private fun randomIdList(): List<String> =
        List(random.nextInt(0, 5)) { listOf("u1", "u2", "g-1", "", "peer-x").random(random) }

    private fun randomSignalMessage(): SignalMessage = SignalMessage(
        id = randomString(),
        fromUserId = randomString(),
        type = randomString(),
        payload = randomString(),
        timestamp = randomLong(),
        callId = randomString(),
        groupId = randomString(),
        groupMemberIds = randomIdList(),
        groupInvite = random.nextBoolean(),
        epoch = randomLong(),
        sequence = randomLong(),
        idempotencyKey = randomString()
    )

    private fun randomSendRequest(): SignalingSendRequest = SignalingSendRequest(
        toUserId = randomString(),
        type = randomString(),
        payload = randomString(),
        callId = randomString(),
        groupId = randomString(),
        groupMemberIds = randomIdList(),
        groupInvite = random.nextBoolean(),
        epoch = randomLong(),
        sequence = randomLong(),
        idempotencyKey = randomString()
    )

    @Test
    fun signalMessageCopyEqualsHashCodeDifferential() {
        repeat(200) {
            val original = randomSignalMessage()
            // 无改动 copy 恒等，且 hashCode 一致。
            assertEquals(original, original.copy())
            assertEquals(original.hashCode(), original.copy().hashCode())
            // 改任一字段后不再相等（新值保证与原值不同）。
            val mutated = when (random.nextInt(0, 12)) {
                0 -> original.copy(id = original.id + "x")
                1 -> original.copy(fromUserId = original.fromUserId + "x")
                2 -> original.copy(type = original.type + "x")
                3 -> original.copy(payload = original.payload + "x")
                4 -> original.copy(timestamp = original.timestamp + 1)
                5 -> original.copy(callId = original.callId + "x")
                6 -> original.copy(groupId = original.groupId + "x")
                7 -> original.copy(groupMemberIds = original.groupMemberIds + "u-new")
                8 -> original.copy(groupInvite = !original.groupInvite)
                9 -> original.copy(epoch = original.epoch + 1)
                10 -> original.copy(sequence = original.sequence + 1)
                else -> original.copy(idempotencyKey = original.idempotencyKey + "x")
            }
            assertNotEquals(original, mutated)
            // 两个独立构造的同值实例相等且 hashCode 一致。
            val rebuilt = SignalMessage(
                id = original.id, fromUserId = original.fromUserId, type = original.type,
                payload = original.payload, timestamp = original.timestamp, callId = original.callId,
                groupId = original.groupId, groupMemberIds = original.groupMemberIds,
                groupInvite = original.groupInvite, epoch = original.epoch,
                sequence = original.sequence, idempotencyKey = original.idempotencyKey
            )
            assertEquals(original, rebuilt)
            assertEquals(original.hashCode(), rebuilt.hashCode())
        }
    }

    @Test
    fun signalingSendRequestCopyEqualsHashCodeDifferential() {
        repeat(200) {
            val original = randomSendRequest()
            assertEquals(original, original.copy())
            assertEquals(original.hashCode(), original.copy().hashCode())
            val mutated = when (random.nextInt(0, 10)) {
                0 -> original.copy(toUserId = original.toUserId + "x")
                1 -> original.copy(type = original.type + "x")
                2 -> original.copy(payload = original.payload + "x")
                3 -> original.copy(callId = original.callId + "x")
                4 -> original.copy(groupId = original.groupId + "x")
                5 -> original.copy(groupMemberIds = original.groupMemberIds + "u-new")
                6 -> original.copy(groupInvite = !original.groupInvite)
                7 -> original.copy(epoch = original.epoch + 1)
                8 -> original.copy(sequence = original.sequence + 1)
                else -> original.copy(idempotencyKey = original.idempotencyKey + "x")
            }
            assertNotEquals(original, mutated)
            val rebuilt = SignalingSendRequest(
                toUserId = original.toUserId, type = original.type, payload = original.payload,
                callId = original.callId, groupId = original.groupId,
                groupMemberIds = original.groupMemberIds, groupInvite = original.groupInvite,
                epoch = original.epoch, sequence = original.sequence,
                idempotencyKey = original.idempotencyKey
            )
            assertEquals(original, rebuilt)
            assertEquals(original.hashCode(), rebuilt.hashCode())
        }
    }

    @Test
    fun signalModelDefaultsAndNativeExceptionsPinned() {
        val minimalMessage = SignalMessage(id = "m1", fromUserId = "u1", type = "offer", payload = "{}", timestamp = 0L)
        assertEquals("", minimalMessage.callId)
        assertEquals("", minimalMessage.groupId)
        assertEquals(emptyList(), minimalMessage.groupMemberIds)
        assertFalse(minimalMessage.groupInvite)
        assertEquals(0L, minimalMessage.epoch)
        assertEquals(0L, minimalMessage.sequence)
        assertEquals("", minimalMessage.idempotencyKey)

        val minimalRequest = SignalingSendRequest(toUserId = "u2", type = "answer", payload = "{}")
        assertEquals("", minimalRequest.callId)
        assertEquals("", minimalRequest.groupId)
        assertEquals(emptyList(), minimalRequest.groupMemberIds)
        assertFalse(minimalRequest.groupInvite)
        assertEquals(0L, minimalRequest.epoch)
        assertEquals(0L, minimalRequest.sequence)
        assertEquals("", minimalRequest.idempotencyKey)

        // 原生异常只透传文案，不改类型层级。
        assertEquals("dl failed", WebRtcNativeLoadException("dl failed").message)
        assertEquals("", WebRtcNativeLoadException("").message)
        assertTrue(WebRtcNativeLoadException("x") is Exception)
        assertEquals("boom", WebRTCException("boom").message)
        assertEquals("", WebRTCException("").message)
        assertTrue(WebRTCException("x") is IllegalStateException)
    }
}
