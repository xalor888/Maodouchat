// Fuzz / random-oracle tests for NotificationCenterDayGrouping.groupByDay
// (checklist line 931, 152nd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: whole-day diff
// against now (future timestamps clamp to TODAY), bucket order by
// ordinal, items inside a bucket newest first (stable).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun groupByDayOracle152(
    items: List<NotificationCenterItem>,
    nowMillis: Long,
): List<Pair<StringBucket, List<NotificationCenterItem>>> {
    val dayMs = 24L * 3600L * 1000L
    return items
        .groupBy { item ->
            val diffDays = ((nowMillis - item.updatedAt) / dayMs).coerceAtLeast(0L)
            when {
                diffDays == 0L -> StringBucket.TODAY
                diffDays == 1L -> StringBucket.YESTERDAY
                diffDays <= 7L -> StringBucket.WEEK
                else -> StringBucket.EARLIER
            }
        }
        .map { (bucket, list) ->
            val ordered = list.sortedByDescending { it.updatedAt }
            bucket to ordered
        }
        .sortedBy { it.first.ordinal }
}

// ---------- generators ----------

internal val DAY_MS152 = 24L * 3600L * 1000L

internal val NOW_POOL152 = listOf(
    0L, 1L, 1_700_000_000_000L, 1_700_000_000_001L, -1L, -DAY_MS152,
    Long.MAX_VALUE - DAY_MS152, Long.MIN_VALUE + DAY_MS152,
)

internal val OFFSET_POOL152 = listOf(
    0L, 1L, -1L, DAY_MS152, -DAY_MS152, DAY_MS152 - 1, -DAY_MS152 + 1,
    2 * DAY_MS152, -2 * DAY_MS152, 7 * DAY_MS152, -7 * DAY_MS152,
    7 * DAY_MS152 + 1, -7 * DAY_MS152 - 1, 8 * DAY_MS152, -8 * DAY_MS152,
    30 * DAY_MS152, -30 * DAY_MS152, 365 * DAY_MS152, -365 * DAY_MS152,
)

internal fun randomItem152(rnd: Random, nowMillis: Long, index: Int): NotificationCenterItem {
    val offset = if (rnd.nextInt(10) < 8) {
        OFFSET_POOL152[rnd.nextInt(OFFSET_POOL152.size)]
    } else {
        rnd.nextLong(-40 * DAY_MS152, 40 * DAY_MS152)
    }
    return NotificationCenterItem(
        id = "n$index",
        type = "MESSAGE",
        mergeKey = "k$index",
        title = "t$index",
        updatedAt = nowMillis + offset,
    )
}

internal fun randomItems152(rnd: Random, nowMillis: Long): List<NotificationCenterItem> {
    val n = rnd.nextInt(0, 9)
    return List(n) { randomItem152(rnd, nowMillis, it) }
}

internal fun dayWireJson152(items: List<NotificationCenterItem>, nowMillis: Long): String {
    val sb = StringBuilder()
    sb.append("{\"now\":").append(nowMillis).append(",\"items\":[")
    for ((i, item) in items.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append("{\"id\":\"").append(item.id).append("\",\"at\":").append(item.updatedAt).append("}")
    }
    sb.append("]}")
    return sb.toString()
}

class NotificationCenterDayGroupingFuzzTest {

    private fun item(id: String, updatedAt: Long) = NotificationCenterItem(
        id = id, type = "MESSAGE", mergeKey = "k", title = "t", updatedAt = updatedAt,
    )

    @Test
    fun pinnedBoundaries() {
        val now = 1_700_000_000_000L
        val today = item("today", now)
        val todayEdge = item("todayEdge", now - 1)
        val yesterday = item("yesterday", now - DAY_MS152)
        val yesterdayEdge = item("yesterdayEdge", now - DAY_MS152 - 1)
        val week = item("week", now - 2 * DAY_MS152)
        val weekEdge = item("weekEdge", now - 7 * DAY_MS152)
        val weekLast = item("weekLast", now - 7 * DAY_MS152 - 1)
        val earlier = item("earlier", now - 8 * DAY_MS152)
        val future = item("future", now + 5 * DAY_MS152)
        val farFuture = item("farFuture", now + 100 * DAY_MS152)
        val items = listOf(earlier, week, today, future, yesterday, farFuture, weekEdge, todayEdge, yesterdayEdge, weekLast)
        val expected = listOf(
            StringBucket.TODAY to listOf(farFuture, future, today, todayEdge),
            StringBucket.YESTERDAY to listOf(yesterday, yesterdayEdge),
            StringBucket.WEEK to listOf(week, weekEdge, weekLast),
            StringBucket.EARLIER to listOf(earlier),
        )
        assertEquals(expected, groupByDayOracle152(items, now))
        assertEquals(groupByDayOracle152(items, now), items.groupByDay(now))
        // Empty input stays empty; a single item lands in its own bucket.
        assertEquals(emptyList(), groupByDayOracle152(emptyList(), now))
        assertEquals(groupByDayOracle152(emptyList(), now), emptyList<NotificationCenterItem>().groupByDay(now))
        assertEquals(
            listOf(StringBucket.EARLIER to listOf(earlier)),
            groupByDayOracle152(listOf(earlier), now),
        )
        assertEquals(
            groupByDayOracle152(listOf(earlier), now),
            listOf(earlier).groupByDay(now),
        )
        // Equal timestamps keep input order inside a bucket (stable sort).
        val a = item("a", now - 3 * DAY_MS152)
        val b = item("b", now - 3 * DAY_MS152)
        val c = item("c", now - 3 * DAY_MS152)
        assertEquals(
            listOf(StringBucket.WEEK to listOf(c, a, b)),
            groupByDayOracle152(listOf(c, a, b), now),
        )
        assertEquals(
            groupByDayOracle152(listOf(c, a, b), now),
            listOf(c, a, b).groupByDay(now),
        )
        // Overflow edge: now near Long.MAX_VALUE, item at Long.MIN_VALUE wraps
        // the subtraction; oracle and production share the same Long ops.
        val overflowNow = Long.MAX_VALUE - DAY_MS152
        val ancient = item("ancient", Long.MIN_VALUE + DAY_MS152)
        assertEquals(
            groupByDayOracle152(listOf(ancient), overflowNow),
            listOf(ancient).groupByDay(overflowNow),
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(152001)
        repeat(1500) {
            val now = if (rnd.nextInt(10) < 7) {
                NOW_POOL152[rnd.nextInt(NOW_POOL152.size)]
            } else {
                rnd.nextLong(-100 * DAY_MS152, 100 * DAY_MS152)
            }
            val items = randomItems152(rnd, now)
            assertEquals(groupByDayOracle152(items, now), items.groupByDay(now))
        }
    }

    @Test
    fun sweepExhaustive() {
        val now = 1_700_000_000_000L
        // Every day-offset boundary, both sides, one item at a time.
        val offsets = listOf<Long>(
            -9 * DAY_MS152, -8 * DAY_MS152 - 1, -8 * DAY_MS152, -7 * DAY_MS152 - 1,
            -7 * DAY_MS152, -2 * DAY_MS152, -DAY_MS152 - 1, -DAY_MS152, -1, 0,
            1, DAY_MS152, 2 * DAY_MS152, 30 * DAY_MS152,
        )
        for (offset in offsets) {
            val one = listOf(item("x", now + offset))
            assertEquals(groupByDayOracle152(one, now), one.groupByDay(now))
        }
        // Bucket order: every pair of buckets, both input orders.
        val perBucket = listOf(
            StringBucket.TODAY to item("t", now),
            StringBucket.YESTERDAY to item("y", now - DAY_MS152),
            StringBucket.WEEK to item("w", now - 3 * DAY_MS152),
            StringBucket.EARLIER to item("e", now - 30 * DAY_MS152),
        )
        for (i in perBucket.indices) {
            for (j in perBucket.indices) {
                if (i == j) continue
                val pair = listOf(perBucket[j].second, perBucket[i].second)
                assertEquals(groupByDayOracle152(pair, now), pair.groupByDay(now))
            }
        }
        // Within-bucket order: all 6 permutations of 3 distinct timestamps.
        val d1 = item("d1", now - 3 * DAY_MS152)
        val d2 = item("d2", now - 3 * DAY_MS152 - 1000)
        val d3 = item("d3", now - 3 * DAY_MS152 - 2000)
        val perms = listOf(
            listOf(d1, d2, d3), listOf(d1, d3, d2), listOf(d2, d1, d3),
            listOf(d2, d3, d1), listOf(d3, d1, d2), listOf(d3, d2, d1),
        )
        for (p in perms) {
            assertEquals(
                listOf(StringBucket.WEEK to listOf(d1, d2, d3)),
                groupByDayOracle152(p, now),
            )
            assertEquals(groupByDayOracle152(p, now), p.groupByDay(now))
        }
    }
}
