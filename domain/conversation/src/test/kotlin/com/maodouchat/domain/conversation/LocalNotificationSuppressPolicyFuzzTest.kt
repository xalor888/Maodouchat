// Fuzz / random-oracle tests for LocalNotificationSuppressPolicy.shouldSuppress
// (checklist line 931, 124th block). Test-only, zero production changes.
// The oracle restates the gate order by hand: notifications-off wins first,
// then the runtime toggle, then the DND window over clamped minutes. Note this
// is a different function from ChatQuietHoursPolicy.shouldSuppress (fuzz-35):
// that one takes a QuietWindow + minute, while this one adds the
// notificationsEnabled / dndRuntimeEnabled / nullable-dndEnabled gates and
// the hour-to-minute fallback with a currentMinute override.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNotificationSuppressPolicyFuzzTest {

// ---------- oracle ----------

// Hand-written clamp. Never coerceIn().
private fun clampOracle(v: Int, lo: Int, hi: Int): Int =
    if (v < lo) lo else if (v > hi) hi else v

private fun suppressOracle(
    notificationsEnabled: Boolean,
    dndStartHour: Int,
    dndEndHour: Int,
    hourOfDay: Int,
    dndRuntimeEnabled: Boolean,
    dndEnabled: Boolean?,
    startMinute: Int?,
    endMinute: Int?,
    currentMinute: Int?
): Boolean {
    if (!notificationsEnabled) return true
    if (!dndRuntimeEnabled) return false
    val minuteOfDay =
        if (currentMinute != null) clampOracle(currentMinute, 0, 1439)
        else clampOracle(hourOfDay, 0, 23) * 60
    val start =
        if (startMinute != null) clampOracle(startMinute, 0, 1439)
        else clampOracle(dndStartHour, 0, 23) * 60
    val end =
        if (endMinute != null) clampOracle(endMinute, 0, 1439)
        else clampOracle(dndEndHour, 0, 23) * 60
    val enabled = dndEnabled ?: true
    if (!enabled) return false
    if (start == end) return false
    return if (start < end) minuteOfDay >= start && minuteOfDay < end
    else minuteOfDay >= start || minuteOfDay < end
}

// ---------- GEN ----------

private val LNS_HOUR_POOL = intArrayOf(
    -5, -1, 0, 1, 3, 7, 8, 9, 12, 17, 21, 22, 23, 24, 25, 28
)

private val LNS_MINUTE_POOL = intArrayOf(
    -100, -1, 0, 1, 59, 60, 359, 360, 419, 420, 479, 480, 539, 540,
    599, 600, 719, 720, 1019, 1020, 1259, 1260, 1319, 1320, 1349,
    1350, 1438, 1439, 1440, 1500, 1600
)

private fun genLnsHour(rnd: Random, poolOnly: Boolean): Int =
    if (poolOnly) LNS_HOUR_POOL[rnd.nextInt(LNS_HOUR_POOL.size)]
    else when (rnd.nextInt(4)) {
        0 -> LNS_HOUR_POOL[rnd.nextInt(LNS_HOUR_POOL.size)]
        1 -> rnd.nextInt(0, 24)
        else -> rnd.nextInt(-5, 29)
    }

private fun genLnsMinute(rnd: Random, poolOnly: Boolean): Int =
    if (poolOnly) LNS_MINUTE_POOL[rnd.nextInt(LNS_MINUTE_POOL.size)]
    else when (rnd.nextInt(4)) {
        0 -> LNS_MINUTE_POOL[rnd.nextInt(LNS_MINUTE_POOL.size)]
        1 -> rnd.nextInt(0, 1440)
        else -> rnd.nextInt(-100, 1601)
    }

// Null with probability 1/3, otherwise a generated value.
private fun genLnsNullableMinute(rnd: Random, poolOnly: Boolean): Int? =
    if (rnd.nextInt(3) == 0) null else genLnsMinute(rnd, poolOnly)

private fun genLnsNullableBool(rnd: Random): Boolean? =
    when (rnd.nextInt(3)) {
        0 -> null
        1 -> true
        else -> false
    }

private fun checkSuppress(
    notificationsEnabled: Boolean,
    dndStartHour: Int,
    dndEndHour: Int,
    hourOfDay: Int,
    dndRuntimeEnabled: Boolean,
    dndEnabled: Boolean?,
    startMinute: Int?,
    endMinute: Int?,
    currentMinute: Int?
) {
    assertEquals(
        suppressOracle(
            notificationsEnabled, dndStartHour, dndEndHour, hourOfDay,
            dndRuntimeEnabled, dndEnabled, startMinute, endMinute, currentMinute
        ),
        LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled, dndStartHour, dndEndHour, hourOfDay,
            dndRuntimeEnabled, dndEnabled, startMinute, endMinute, currentMinute
        ),
        "notif=$notificationsEnabled runtime=$dndRuntimeEnabled dnd=$dndEnabled " +
            "startH=$dndStartHour endH=$dndEndHour hour=$hourOfDay " +
            "startM=$startMinute endM=$endMinute curM=$currentMinute"
    )
}

    @Test
    fun shouldSuppressRandom() {
        val rnd = Random(42)
        repeat(300) {
            checkSuppress(
                notificationsEnabled = rnd.nextBoolean(),
                dndStartHour = genLnsHour(rnd, poolOnly = false),
                dndEndHour = genLnsHour(rnd, poolOnly = false),
                hourOfDay = genLnsHour(rnd, poolOnly = false),
                dndRuntimeEnabled = rnd.nextBoolean(),
                dndEnabled = genLnsNullableBool(rnd),
                startMinute = genLnsNullableMinute(rnd, poolOnly = false),
                endMinute = genLnsNullableMinute(rnd, poolOnly = false),
                currentMinute = genLnsNullableMinute(rnd, poolOnly = false)
            )
        }
    }

    @Test
    fun shouldSuppressEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            checkSuppress(
                notificationsEnabled = rnd.nextBoolean(),
                dndStartHour = genLnsHour(rnd, poolOnly = true),
                dndEndHour = genLnsHour(rnd, poolOnly = true),
                hourOfDay = genLnsHour(rnd, poolOnly = true),
                dndRuntimeEnabled = rnd.nextBoolean(),
                dndEnabled = genLnsNullableBool(rnd),
                startMinute = genLnsNullableMinute(rnd, poolOnly = true),
                endMinute = genLnsNullableMinute(rnd, poolOnly = true),
                currentMinute = genLnsNullableMinute(rnd, poolOnly = true)
            )
        }
    }

    @Test
    fun shouldSuppressPinned() {
        // Global off wins over everything, including a disabled runtime.
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = false, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 12
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = false, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 12, dndRuntimeEnabled = false
            )
        )
        // Runtime toggle off means never suppress (notifications on).
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 12, dndRuntimeEnabled = false
            )
        )
        // Explicitly disabled DND never suppresses.
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 23, dndEnabled = false
            )
        )
        // Null dndEnabled defaults to true.
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 12, dndEnabled = null
            )
        )
        // Equal bounds = no window, even at the bound itself.
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 22, hourOfDay = 22
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 12,
                startMinute = 600, endMinute = 600
            )
        )
        // Daytime window [09:00, 17:00): start inclusive, end exclusive.
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 9
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 12
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 17
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 8
            )
        )
        // Overnight window [22:00, 07:00): both sides of midnight suppress.
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 23
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 3
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 22
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 7
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 21
            )
        )
        // currentMinute overrides the hour path entirely.
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 3, currentMinute = 600
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 12, currentMinute = 2000
            )
        )
        // Out-of-range inputs clamp: hour 25 -> 23:00, minute 2000 -> 1439.
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 25
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = -1
            )
        )
        // startMinute 1500 clamps to 1439, so [1439, 07:00) is overnight.
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 7, hourOfDay = 12, startMinute = 1500
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 7, hourOfDay = 12,
                startMinute = 1500, currentMinute = 1439
            )
        )
        // Oracle cross-checks on the same pinned inputs.
        assertEquals(
            suppressOracle(true, 9, 17, 12, true, null, null, null, null),
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 9,
                dndEndHour = 17, hourOfDay = 12
            )
        )
        assertEquals(
            suppressOracle(true, 22, 7, 23, true, true, null, null, null),
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 23
            )
        )
        assertEquals(
            suppressOracle(false, 22, 7, 12, false, false, 0, 0, 0),
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = false, dndStartHour = 22,
                dndEndHour = 7, hourOfDay = 12,
                dndRuntimeEnabled = false, dndEnabled = false,
                startMinute = 0, endMinute = 0, currentMinute = 0
            )
        )
    }
}
