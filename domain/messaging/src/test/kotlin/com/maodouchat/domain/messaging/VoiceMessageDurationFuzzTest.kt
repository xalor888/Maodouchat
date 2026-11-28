package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [VoiceMessageDuration] (checklist line 931, 32nd block).
 * Test-only, zero production changes. The oracle below re-implements the
 * truncate / round-up / round-down-with-floor division rules by hand; a Python
 * mini-model differentially checks the oracle transcription against an
 * independent production transcription.
 * Note: formatDuration has no hours field and no sign handling, so negative
 * inputs render like "0:-1" (pinned as current behaviour, not changed);
 * ceilSeconds overflows for millis very close to Long.MAX_VALUE because
 * millis + 999 wraps (also pinned, not changed).
 */
class VoiceMessageDurationFuzzTest {

    private fun oracleFormatDuration(millis: Long): String {
        val seconds = millis / 1000L // Kotlin division truncates toward zero
        val minutes = seconds / 60L
        val secs = seconds % 60L // Kotlin remainder carries the dividend's sign
        return "%d:%02d".format(minutes, secs)
    }

    private fun oracleCeilSeconds(millis: Long): Long? =
        if (millis <= 0L) null else (millis + 999L) / 1000L

    private fun oracleFloorSecondsAtLeastOne(millis: Long): Long =
        maxOf(millis / 1000L, 1L)

    @Test
    fun randomFormatDurationMatchesOracle() {
        val r = Random(3231)
        val boundaryMillis = listOf(
            0L, 1L, 999L, 1_000L, 1_001L, 59_000L, 59_999L, 60_000L, 61_500L,
            3_599_999L, 3_600_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE
        )
        repeat(300) {
            val millis = if (r.nextInt(4) == 0) boundaryMillis[r.nextInt(boundaryMillis.size)]
            else r.nextLong(0L, Long.MAX_VALUE)
            val formatted = VoiceMessageDuration.formatDuration(millis)
            assertEquals(oracleFormatDuration(millis), formatted, "millis=$millis")
            // Round-trip: "m:ss" parses back to the truncated whole seconds.
            val parts = formatted.split(":")
            assertEquals(2, parts.size)
            assertEquals(millis / 1000L, parts[0].toLong() * 60L + parts[1].toLong())
        }
    }

    @Test
    fun randomCeilSecondsMatchesOracle() {
        val r = Random(3232)
        val boundaryMillis = listOf(1L, 2L, 999L, 1_000L, 1_001L, 59_999L, 60_000L, Long.MAX_VALUE - 1_000L)
        repeat(300) {
            val millis = if (r.nextInt(4) == 0) boundaryMillis[r.nextInt(boundaryMillis.size)]
            else r.nextLong(1L, Long.MAX_VALUE - 1_000L)
            val ceiled = VoiceMessageDuration.ceilSeconds(millis)
            assertEquals(oracleCeilSeconds(millis), ceiled, "millis=$millis")
            // Ceil is either the truncated seconds or one more.
            assertTrue(ceiled == millis / 1000L || ceiled == millis / 1000L + 1L)
        }
    }

    @Test
    fun randomFloorSecondsMatchesOracle() {
        val r = Random(3233)
        repeat(300) {
            val millis = r.nextLong()
            assertEquals(
                oracleFloorSecondsAtLeastOne(millis),
                VoiceMessageDuration.floorSecondsAtLeastOne(millis),
                "millis=$millis"
            )
            assertTrue(VoiceMessageDuration.floorSecondsAtLeastOne(millis) >= 1L)
        }
    }

    @Test
    fun negativeAndZeroPins() {
        assertEquals("0:00", VoiceMessageDuration.formatDuration(0L))
        assertEquals("0:00", VoiceMessageDuration.formatDuration(-1L))
        assertEquals("0:00", VoiceMessageDuration.formatDuration(-999L))
        // No sign handling: the seconds remainder stays negative (current behaviour).
        assertEquals("0:-1", VoiceMessageDuration.formatDuration(-1_000L))
        assertEquals("0:-1", VoiceMessageDuration.formatDuration(-1_500L))
        assertEquals("-1:-1", VoiceMessageDuration.formatDuration(-61_000L))
        assertNull(VoiceMessageDuration.ceilSeconds(0L))
        assertNull(VoiceMessageDuration.ceilSeconds(-500L))
        assertEquals(1L, VoiceMessageDuration.floorSecondsAtLeastOne(0L))
        assertEquals(1L, VoiceMessageDuration.floorSecondsAtLeastOne(-1_000L))
    }

    @Test
    fun ceilSecondsOverflowPins() {
        // millis + 999 wraps just below Long.MIN_VALUE, so the "ceil" goes negative.
        assertEquals(-9_223_372_036_854_774L, VoiceMessageDuration.ceilSeconds(Long.MAX_VALUE))
        // One thousand below the top, millis + 999 lands exactly on Long.MAX_VALUE.
        assertEquals(9_223_372_036_854_775L, VoiceMessageDuration.ceilSeconds(Long.MAX_VALUE - 999L))
        assertEquals(9_223_372_036_854_775L, VoiceMessageDuration.ceilSeconds(Long.MAX_VALUE - 1_000L))
        assertEquals("153722867280912:55", VoiceMessageDuration.formatDuration(Long.MAX_VALUE))
    }
}
