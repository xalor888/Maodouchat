package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifSearchPolicyTest {
    private val samples = listOf(
        LocalGifItem("1", "content://1", "party_cat.gif", dateAddedSec = 30),
        LocalGifItem("2", "content://2", "thumbs-up.gif", dateAddedSec = 20),
        LocalGifItem("3", "content://3", "dance_floor.gif", dateAddedSec = 10),
    )

    @Test
    fun `filter by name tokens`() {
        val hits = GifSearchPolicy.filterAndSort(samples, "party")
        assertEquals(listOf("1"), hits.map { it.id })
        assertTrue(GifSearchPolicy.filterAndSort(samples, "up").any { it.id == "2" })
    }

    @Test
    fun `recent first then date`() {
        val ordered = GifSearchPolicy.filterAndSort(samples, "", recentIds = listOf("3", "1"))
        assertEquals(listOf("3", "1", "2"), ordered.map { it.id })
    }

    @Test
    fun `pushRecent dedupes and caps`() {
        val next = GifSearchPolicy.pushRecent(listOf("a", "b", "c"), "b", max = 2)
        assertEquals(listOf("b", "a"), next)
    }
}
