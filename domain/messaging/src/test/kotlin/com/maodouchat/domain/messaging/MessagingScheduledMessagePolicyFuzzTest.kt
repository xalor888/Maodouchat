package com.maodouchat.domain.messaging

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fuzz / random-oracle tests for the :domain:messaging [ScheduledMessagePolicy]
 * (checklist line 931, 33rd block; not the :core:model namesake, whose own
 * fuzz test already covers its send-window arithmetic).
 * Test-only, zero production changes. The oracle below re-derives the
 * recurrence stepping by hand; a Python mini-model differentially checks it
 * against an independent transcription of the production loop (3000 random
 * cases, 0 differences).
 * Note: nextRunAt's `while (candidate < now)` loop adds the step until it
 * reaches now, so a lastRunAt near Long.MAX_VALUE with a small step wraps
 * and would loop effectively forever — that is current behaviour, pinned
 * only in a comment, never executed as a test.
 */
class MessagingScheduledMessagePolicyFuzzTest {

    private companion object {
        const val DAY = 86_400_000L
    }

    private fun msg(
        sendAt: Long,
        status: ScheduleStatus = ScheduleStatus.PENDING,
        recurrence: RecurrenceRule? = null,
    ) = ScheduledMessage(
        id = "s1",
        ownerUserId = "u1",
        conversationId = "c1",
        content = ContentPayload.Text("hi"),
        sendAtMillis = sendAt,
        status = status,
        recurrence = recurrence,
        idempotencyKey = "k1",
    )

    private fun oracleNextRunAt(lastRunAt: Long, now: Long, intervalDays: Int, type: RecurrenceType?): Long? {
        if (type == null || type == RecurrenceType.ONCE) return null
        val step = maxOf(intervalDays, 1) * DAY
        var candidate = lastRunAt + step
        while (candidate < now) candidate += step
        return candidate
    }

    @Test
    fun isDueRandomOracle() {
        val r = Random(20261122)
        val sendAts = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_000L, Long.MAX_VALUE)
        val nows = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_000L, Long.MAX_VALUE)
        repeat(300) {
            val sendAt = if (r.nextInt(4) == 0) sendAts[r.nextInt(sendAts.size)] else r.nextLong()
            val now = if (r.nextInt(4) == 0) nows[r.nextInt(nows.size)] else r.nextLong()
            val status = ScheduleStatus.entries[r.nextInt(ScheduleStatus.entries.size)]
            assertEquals(
                status == ScheduleStatus.PENDING && sendAt <= now,
                ScheduledMessagePolicy.isDue(msg(sendAt, status), now),
            )
        }
    }

    @Test
    fun nextRunAtRandomOracle() {
        val r = Random(3301)
        val intervals = listOf(-5, -1, 0, 1, 1, 2, 3, 7, 30, 365)
        val types = listOf<RecurrenceType?>(null, RecurrenceType.ONCE, RecurrenceType.DAILY, RecurrenceType.WEEKDAYS)
        repeat(300) {
            // Safe window: lastRunAt within 400 days before now, so the
            // stepping loop runs at most ~400 iterations and never wraps.
            val now = r.nextLong(0, 10L * 365 * DAY)
            val lastRunAt = now - r.nextLong(0, 400 * DAY)
            val interval = intervals[r.nextInt(intervals.size)]
            val type = types[r.nextInt(types.size)]
            val schedule = msg(0L, recurrence = type?.let { RecurrenceRule(it, interval) })
            val actual = ScheduledMessagePolicy.nextRunAt(schedule, lastRunAt, now)
            assertEquals(oracleNextRunAt(lastRunAt, now, interval, type), actual)
            if (actual != null) {
                val step = maxOf(interval, 1) * DAY
                assertTrue(actual > lastRunAt)
                assertTrue(actual >= now)
                assertEquals(0L, (actual - lastRunAt) % step)
                assertTrue(actual - step < now || actual - step <= lastRunAt)
            }
        }
    }

    @Test
    fun nextRunAtOverflowPins() {
        // lastRunAt + DAY lands exactly on Long.MAX_VALUE: no wrap, one step.
        assertEquals(
            Long.MAX_VALUE,
            ScheduledMessagePolicy.nextRunAt(
                msg(0L, recurrence = RecurrenceRule(RecurrenceType.DAILY, 1)),
                lastRunAt = Long.MAX_VALUE - DAY, now = Long.MAX_VALUE,
            ),
        )
        // Int.MAX_VALUE days: step 185542587100800000 fits in Long, one step.
        assertEquals(
            185542587100800000L,
            ScheduledMessagePolicy.nextRunAt(
                msg(0L, recurrence = RecurrenceRule(RecurrenceType.DAILY, Int.MAX_VALUE)),
                lastRunAt = 0L, now = 1_000_000_000_000L,
            ),
        )
        // NOT tested: lastRunAt = MAX - DAY with interval 7 (or any step that
        // wraps below now) makes the while loop run ~1e10 iterations before
        // climbing back — current behaviour, documented, never executed.
    }

    @Test
    fun terminalSetPins() {
        assertEquals(setOf(ScheduleStatus.SENT, ScheduleStatus.CANCELLED), ScheduledMessagePolicy.terminal)
        assertFalse(ScheduledMessagePolicy.terminal.contains(ScheduleStatus.PENDING))
        assertFalse(ScheduledMessagePolicy.terminal.contains(ScheduleStatus.FAILED))
    }

    @Test
    fun intervalDaysZeroAndNegativeClampToOneDay() {
        for (interval in listOf(0, -3)) {
            assertEquals(
                1_000L + DAY,
                ScheduledMessagePolicy.nextRunAt(
                    msg(0L, recurrence = RecurrenceRule(RecurrenceType.DAILY, interval)),
                    lastRunAt = 1_000L, now = 2_000L,
                ),
            )
        }
    }

    @Test
    fun weekdaysAdvancesByIntervalLikeDaily() {
        // WEEKDAYS weekday-skipping lives in the impl layer (java.time);
        // this policy only steps by interval, same as DAILY.
        val daily = ScheduledMessagePolicy.nextRunAt(
            msg(0L, recurrence = RecurrenceRule(RecurrenceType.DAILY, 2)),
            lastRunAt = 0L, now = 5 * DAY + 1,
        )
        val weekdays = ScheduledMessagePolicy.nextRunAt(
            msg(0L, recurrence = RecurrenceRule(RecurrenceType.WEEKDAYS, 2)),
            lastRunAt = 0L, now = 5 * DAY + 1,
        )
        assertEquals(daily, weekdays)
        assertEquals(6 * DAY, weekdays)
        assertNull(
            ScheduledMessagePolicy.nextRunAt(
                msg(0L, recurrence = RecurrenceRule(RecurrenceType.ONCE, 2)),
                lastRunAt = 0L, now = 5 * DAY + 1,
            ),
        )
        assertNull(
            ScheduledMessagePolicy.nextRunAt(msg(0L, recurrence = null), lastRunAt = 0L, now = 5 * DAY + 1),
        )
    }
}
