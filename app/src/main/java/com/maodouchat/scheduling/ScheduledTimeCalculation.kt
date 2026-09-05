package com.maodouchat.scheduling

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Timezone- and DST-aware recurrence calculator (M09).
 * Preserves local wall-clock time across Daylight Saving Time shifts and handles weekday skipping.
 */
object ScheduledTimeCalculation {
    private const val DAY_MILLIS = 86_400_000L

    fun nextRepeatSendAt(
        sendAtMillis: Long,
        repeatIntervalMs: Long,
        weekdaysOnly: Boolean,
        timeZoneId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        if (repeatIntervalMs <= 0L) return sendAtMillis
        val zone = resolveZone(timeZoneId)
        val isWholeDays = (repeatIntervalMs % DAY_MILLIS) == 0L
        val daysStep = (repeatIntervalMs / DAY_MILLIS).coerceAtLeast(1L)

        var zdt = Instant.ofEpochMilli(sendAtMillis).atZone(zone)

        do {
            if (isWholeDays) {
                // Advance by calendar days to maintain exact wall-clock time across DST transitions
                zdt = zdt.plusDays(daysStep)
            } else {
                zdt = zdt.plus(Duration.ofMillis(repeatIntervalMs))
            }

            if (weekdaysOnly) {
                while (zdt.dayOfWeek == DayOfWeek.SATURDAY || zdt.dayOfWeek == DayOfWeek.SUNDAY) {
                    zdt = zdt.plusDays(1L)
                }
            }
        } while (zdt.toInstant().toEpochMilli() <= nowMillis)

        return zdt.toInstant().toEpochMilli()
    }

    fun resolveZone(timeZoneId: String?): ZoneId {
        if (timeZoneId.isNullOrBlank()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }
    }
}
