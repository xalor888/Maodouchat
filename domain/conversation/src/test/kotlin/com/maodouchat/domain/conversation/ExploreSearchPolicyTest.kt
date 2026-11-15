package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreSearchPolicyTest {

    private data class Item(val content: String, val authorName: String, val authorId: String)

    private val items = listOf(
        Item("hello world", "Alice", "u1"),
        Item("goodbye", "Bob", "u2"),
        Item("HELLO again", "Carol", "alice_99"),
    )

    @Test
    fun `matches content case-insensitively`() {
        assertTrue(ExploreSearchPolicy.matches("Hello World", "Alice", "u1", "hello"))
        assertFalse(ExploreSearchPolicy.matches("goodbye", "Bob", "u2", "hello"))
    }

    @Test
    fun `matches author name and id`() {
        assertTrue(ExploreSearchPolicy.matches("x", "Alice", "u1", "ali"))
        assertTrue(ExploreSearchPolicy.matches("x", "Alice", "u1", "U1"))
        assertTrue(ExploreSearchPolicy.matches("x", "Bob", "u2", "u2"))
        assertFalse(ExploreSearchPolicy.matches("x", "Bob", "u2", "alice"))
    }

    @Test
    fun `filter returns all on blank query`() {
        val out = ExploreSearchPolicy.filter(items, "   ", { it.content }, { it.authorName }, { it.authorId })
        assertEquals(items, out)
    }

    @Test
    fun `filter trims query and matches any field`() {
        val out = ExploreSearchPolicy.filter(items, " alice ", { it.content }, { it.authorName }, { it.authorId })
        assertEquals(listOf(items[0], items[2]), out)
    }

    @Test
    fun `filter returns empty when nothing matches`() {
        val out = ExploreSearchPolicy.filter(items, "zzz", { it.content }, { it.authorName }, { it.authorId })
        assertTrue(out.isEmpty())
    }
}
