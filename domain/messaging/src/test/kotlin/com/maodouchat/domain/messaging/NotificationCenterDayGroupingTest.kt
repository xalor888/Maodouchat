package com.maodouchat.domain.messaging

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationCenterDayGroupingTest {

    private val now = 1_780_000_000_000L
    private val dayMs = 24L * 3600L * 1000L

    private fun item(id: String, updatedAt: Long) = NotificationCenterItem(
        id = id,
        type = "MESSAGE",
        mergeKey = id,
        title = id,
        updatedAt = updatedAt
    )

    private fun buckets(items: List<NotificationCenterItem>) =
        items.groupByDay(now).map { it.first }

    @Test
    fun sameDayItemsAreToday() {
        val items = listOf(item("a", now), item("b", now - 3_600_000L))
        assertEquals(listOf(StringBucket.TODAY), buckets(items))
    }

    @Test
    fun oneDayAgoIsYesterday() {
        val items = listOf(item("a", now - dayMs), item("b", now - dayMs - 3_600_000L))
        assertEquals(listOf(StringBucket.YESTERDAY), buckets(items))
    }

    @Test
    fun withinSevenDaysIsWeek() {
        val items = listOf(item("a", now - 3 * dayMs), item("b", now - 7 * dayMs))
        assertEquals(listOf(StringBucket.WEEK), buckets(items))
    }

    @Test
    fun eightDaysAgoIsEarlier() {
        val items = listOf(item("a", now - 8 * dayMs), item("b", now - 30 * dayMs))
        assertEquals(listOf(StringBucket.EARLIER), buckets(items))
    }

    @Test
    fun futureTimestampIsForcedToToday() {
        val items = listOf(item("a", now + dayMs), item("b", now + 10 * dayMs))
        assertEquals(listOf(StringBucket.TODAY), buckets(items))
    }

    @Test
    fun itemsWithinBucketAreNewestFirst() {
        val items = listOf(
            item("old", now - 5 * 3_600_000L),
            item("new", now - 1 * 3_600_000L)
        )
        val grouped = items.groupByDay(now)
        assertEquals(1, grouped.size)
        assertEquals(listOf("new", "old"), grouped[0].second.map { it.id })
    }

    @Test
    fun bucketsAreOrderedTodayFirst() {
        val items = listOf(
            item("earlier", now - 10 * dayMs),
            item("week", now - 3 * dayMs),
            item("today", now),
            item("yesterday", now - dayMs)
        )
        assertEquals(
            listOf(
                StringBucket.TODAY,
                StringBucket.YESTERDAY,
                StringBucket.WEEK,
                StringBucket.EARLIER
            ),
            buckets(items)
        )
    }

    @Test
    fun emptyListGroupsToEmpty() {
        assertTrue(emptyList<NotificationCenterItem>().groupByDay(now).isEmpty())
    }
}
