package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MissedCallSuffixKindFuzzTest {

    private val random = Random(2026_11_22L)

    private val durationPool = listOf("", "03:12", " ", "0:00", "1:02:03", "  ", "\t")

    private val durationChars = "0123456789:ab ".toList()

    private fun randomDuration(): String =
        if (random.nextInt(2) == 0) durationPool[random.nextInt(durationPool.size)]
        else buildString {
            repeat(random.nextInt(8)) { append(durationChars[random.nextInt(durationChars.size)]) }
        }

    // 判定表的逐字复述：未接恒 BADGE；已接看时长文案是否非空（非空白）。
    private fun oracleSuffix(state: State, durationText: String): MissedCallSuffixKind =
        when (state) {
            State.MISSED -> MissedCallSuffixKind.BADGE
            State.ANSWERED ->
                if (durationText.isNotEmpty()) MissedCallSuffixKind.DURATION
                else MissedCallSuffixKind.NONE
        }

    @Test
    fun `suffix matches oracle over random states and durations`() {
        repeat(200) {
            val state = if (random.nextBoolean()) State.MISSED else State.ANSWERED
            val duration = randomDuration()
            assertEquals(
                oracleSuffix(state, duration),
                missedCallSuffixKind(state, duration),
                "state=$state duration=$duration",
            )
        }
    }

    @Test
    fun `missed always shows badge regardless of duration`() {
        repeat(100) {
            val duration = randomDuration()
            assertEquals(
                MissedCallSuffixKind.BADGE,
                missedCallSuffixKind(State.MISSED, duration),
                "duration=$duration",
            )
        }
    }

    @Test
    fun `answered duration emptiness decides the suffix`() {
        repeat(100) {
            val duration = randomDuration()
            val expected =
                if (duration.isNotEmpty()) MissedCallSuffixKind.DURATION
                else MissedCallSuffixKind.NONE
            assertEquals(
                expected,
                missedCallSuffixKind(State.ANSWERED, duration),
                "duration=$duration",
            )
        }
    }

    @Test
    fun `whitespace-only duration still counts as a duration`() {
        // isNotEmpty 而非 isNotBlank：纯空白时长文案仍挂 DURATION 后缀。
        assertEquals(
            MissedCallSuffixKind.DURATION,
            missedCallSuffixKind(State.ANSWERED, " "),
        )
        assertEquals(
            MissedCallSuffixKind.DURATION,
            missedCallSuffixKind(State.ANSWERED, "\t"),
        )
        assertEquals(
            MissedCallSuffixKind.NONE,
            missedCallSuffixKind(State.ANSWERED, ""),
        )
    }

    @Test
    fun `every state and duration pair is deterministic`() {
        repeat(100) {
            val state = if (random.nextBoolean()) State.MISSED else State.ANSWERED
            val duration = randomDuration()
            assertEquals(
                missedCallSuffixKind(state, duration),
                missedCallSuffixKind(state, duration),
                "state=$state duration=$duration",
            )
        }
    }
}
