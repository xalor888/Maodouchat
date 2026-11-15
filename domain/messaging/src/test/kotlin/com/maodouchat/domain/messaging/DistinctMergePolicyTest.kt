package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DistinctMergePolicyTest {

    @Test
    fun `keeps first occurrence on duplicate id`() {
        assertEquals(
            listOf("a", "b", "c"),
            DistinctMergePolicy.mergeById(listOf("a", "b"), listOf("b", "c")) { it }
        )
    }

    @Test
    fun `drops incoming duplicates`() {
        assertEquals(
            listOf("a"),
            DistinctMergePolicy.mergeById(listOf("a"), listOf("a", "a")) { it }
        )
    }

    @Test
    fun `empty existing returns incoming deduplicated`() {
        assertEquals(listOf("a"), DistinctMergePolicy.mergeById(emptyList(), listOf("a", "a")) { it })
    }

    @Test
    fun `empty incoming returns existing unchanged`() {
        assertEquals(listOf("a", "b"), DistinctMergePolicy.mergeById(listOf("a", "b"), emptyList()) { it })
    }

    @Test
    fun `works for messages and preserves order`() {
        val m1 = Message(id = "m1", chatId = "c1", senderId = "u1", content = "one", timestamp = 1L)
        val m2 = Message(id = "m2", chatId = "c1", senderId = "u1", content = "two", timestamp = 2L)
        val m1dup = m1.copy(content = "one-new")
        assertEquals(
            listOf(m1, m2),
            DistinctMergePolicy.mergeById(listOf(m1), listOf(m2, m1dup)) { it.id }
        )
    }
}
