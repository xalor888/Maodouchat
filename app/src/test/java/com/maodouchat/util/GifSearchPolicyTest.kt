package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `normalizeQuery lowercases trims and caps`() {
        assertEquals("hello world", GifSearchPolicy.normalizeQuery("  HeLLo World  "))
        assertEquals("", GifSearchPolicy.normalizeQuery(null))
        assertEquals(80, GifSearchPolicy.normalizeQuery("q".repeat(100)).length)
    }

    @Test
    fun `matches ignores case and separators`() {
        val item = LocalGifItem("2", "content://2", "thumbs-up.gif")
        assertTrue(GifSearchPolicy.matches(item, "THUMBS UP"))
        assertFalse(GifSearchPolicy.matches(item, "zzz"))
    }

    @Test
    fun `empty query returns all ordered by date`() {
        val all = GifSearchPolicy.filterAndSort(samples, "")
        assertEquals(listOf("1", "2", "3"), all.map { it.id })
    }

    @Test
    fun `limit clamps to at least one`() {
        val one = GifSearchPolicy.filterAndSort(samples, "", limit = 0)
        assertEquals(1, one.size)
    }

    @Test
    fun `pushRecent ignores blank id`() {
        assertEquals(listOf("a"), GifSearchPolicy.pushRecent(listOf("a"), "  "))
        assertEquals(listOf("b", "a"), GifSearchPolicy.pushRecent(listOf("a"), "b"))
    }
}
