package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifSearchPolicyTest {

    private fun item(id: String, name: String, date: Long = 0L) =
        LocalGifItem(id, "content://$id", name, dateAddedSec = date)

    @Test
    fun `normalizeQuery trims lowercases and caps length`() {
        assertEquals("cat", GifSearchPolicy.normalizeQuery("  CAT "))
        assertEquals(80, GifSearchPolicy.normalizeQuery("a".repeat(100)).length)
    }

    @Test
    fun `matches ignores underscores and dashes in names`() {
        val gif = item("1", "funny_cat")
        assertTrue(GifSearchPolicy.matches(gif, "funny cat"))
        assertTrue(GifSearchPolicy.matches(item("2", "funny-cat"), "funny cat"))
    }

    @Test
    fun `filterAndSort puts recent ids first`() {
        val items = listOf(item("a", "cat", 10L), item("b", "cat", 20L))
        val result = GifSearchPolicy.filterAndSort(items, "cat", listOf("b"))
        assertEquals(listOf("b", "a"), result.map { it.id })
    }

    @Test
    fun `pushRecent dedupes and moves to front`() {
        assertEquals(listOf("c", "a", "b"), GifSearchPolicy.pushRecent(listOf("a", "b"), "c"))
        assertEquals(listOf("a", "b"), GifSearchPolicy.pushRecent(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), GifSearchPolicy.pushRecent(listOf("a", "b"), "  "))
    }
}
