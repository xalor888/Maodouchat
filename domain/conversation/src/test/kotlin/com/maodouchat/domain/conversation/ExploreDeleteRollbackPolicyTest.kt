package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreDeleteRollbackPolicyTest {

    @Test
    fun `restore inserts at original index`() {
        val out = ExploreDeleteRollbackPolicy.restore(listOf("a", "c"), "b", 1)
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun `restore beyond size appends at end`() {
        val out = ExploreDeleteRollbackPolicy.restore(listOf("a", "b"), "c", 9)
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun `restore with negative index inserts at front`() {
        val out = ExploreDeleteRollbackPolicy.restore(listOf("b", "c"), "a", -1)
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun `restore into empty list`() {
        val out = ExploreDeleteRollbackPolicy.restore(emptyList(), "a", 0)
        assertEquals(listOf("a"), out)
    }

    @Test
    fun `restore leaves the original list unchanged`() {
        val original = listOf("a", "c")
        ExploreDeleteRollbackPolicy.restore(original, "b", 1)
        assertEquals(listOf("a", "c"), original)
    }
}
