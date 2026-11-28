package com.maodouchat.domain.calls

import com.maodouchat.core.model.MissedCall
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 未接来电记录组装 fuzz（清单 931 行第二十二块）。
 * 只加测试、零生产改动：`MissedCallRecordPolicy.kt` 的 `displayCallerName`/`buildMissedCallRecord`。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class MissedCallRecordPolicyFuzzTest {

    private val textAlphabet = "abcXYZ019 _-.\"\\\n\t中"

    private fun randomText(r: Random, length: Int): String =
        buildString { repeat(length) { append(textAlphabet[r.nextInt(textAlphabet.length)]) } }

    // 名字池特意混入空白串：空串/纯空格/制表换行都要走 fromUserId 回落。
    private fun randomCallerName(r: Random): String = when (r.nextInt(4)) {
        0 -> ""
        1 -> " ".repeat(r.nextInt(1, 4))
        2 -> "\t\n".repeat(r.nextInt(1, 3))
        else -> randomText(r, r.nextInt(0, 10))
    }

    private fun oracleRecord(
        missedId: String,
        fromUserId: String,
        callerName: String,
        isVideo: Boolean,
        nowMs: Long,
        isGroup: Boolean,
    ): MissedCallRecord {
        val name = if (callerName.isBlank()) fromUserId else callerName
        return MissedCallRecord(
            missedCall = MissedCall(
                id = missedId,
                callerId = fromUserId,
                callerName = name,
                callType = if (isVideo) "VIDEO" else "AUDIO",
                receivedAt = nowMs,
            ),
            callLogEntry = CallLogEntry(
                id = missedId,
                peerId = fromUserId,
                peerName = name,
                isVideo = isVideo,
                direction = Direction.INCOMING,
                state = State.MISSED,
                startedAt = nowMs,
                isGroup = isGroup,
            ),
        )
    }

    @Test
    fun `build record matches oracle on random inputs`() {
        val r = Random(0x2EC0_0622L)
        repeat(200) {
            val missedId = randomText(r, r.nextInt(0, 8))
            val fromUserId = randomText(r, r.nextInt(0, 8))
            val callerName = randomCallerName(r)
            val isVideo = r.nextBoolean()
            val nowMs = when (r.nextInt(5)) {
                0 -> Long.MIN_VALUE
                1 -> Long.MAX_VALUE
                2 -> 0L
                else -> r.nextLong(-1_000_000_000_000L, 1_000_000_000_000L)
            }
            val isGroup = r.nextBoolean()
            assertEquals(
                oracleRecord(missedId, fromUserId, callerName, isVideo, nowMs, isGroup),
                buildMissedCallRecord(missedId, fromUserId, callerName, isVideo, nowMs, isGroup),
            )
        }
    }

    @Test
    fun `displayCallerName matches oracle on random names`() {
        val r = Random(0xCA11_0622L)
        repeat(200) {
            val name = randomCallerName(r)
            val fromUserId = randomText(r, r.nextInt(0, 8))
            val expected = if (name.isBlank()) fromUserId else name
            assertEquals(expected, displayCallerName(name, fromUserId))
        }
    }

    @Test
    fun `blank variants all fall back to fromUserId`() {
        for (blank in listOf("", " ", "   ", "\t", "\n", " \t\n ", "\u2000", "\u3000", "\u00a0")) {
            assertEquals("u1", displayCallerName(blank, "u1"))
        }
        assertEquals("Alice", displayCallerName("Alice", "u1"))
        assertEquals(" Alice ", displayCallerName(" Alice ", "u1"))
        // isBlank 走 Char.isWhitespace，NBSP 属 Zs 也算空白，因此同样回落；
        // 而 U+0085 (NEL) 不在 Kotlin 空白集里，保留原值。
        assertEquals("\u0085", displayCallerName("\u0085", "u1"))
    }

    @Test
    fun `record pins shared id timestamp and defaults`() {
        val record = buildMissedCallRecord("m1", "u1", "Alice", true, 1000L)
        assertEquals("m1", record.missedCall.id)
        assertEquals("m1", record.callLogEntry.id)
        assertEquals(1000L, record.missedCall.receivedAt)
        assertEquals(1000L, record.callLogEntry.startedAt)
        assertEquals("VIDEO", record.missedCall.callType)
        assertEquals(Direction.INCOMING, record.callLogEntry.direction)
        assertEquals(State.MISSED, record.callLogEntry.state)
        assertEquals(0L, record.callLogEntry.durationMs)
        assertFalse(record.callLogEntry.isGroup)
        assertFalse(record.missedCall.isRead)
        assertEquals("Alice", record.missedCall.callerName)
        assertEquals("Alice", record.callLogEntry.peerName)
        // isGroup 默认 false；显式 true 才进组。
        assertTrue(buildMissedCallRecord("m2", "u1", "Alice", false, 2000L, isGroup = true).callLogEntry.isGroup)
        assertFalse(buildMissedCallRecord("m2", "u1", "Alice", false, 2000L).callLogEntry.isGroup)
        // 音频映射。
        assertEquals("AUDIO", buildMissedCallRecord("m3", "u1", "Alice", false, 3000L).missedCall.callType)
    }
}
