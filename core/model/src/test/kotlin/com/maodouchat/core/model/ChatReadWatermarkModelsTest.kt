package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadWatermarkModelsTest {

    @Test
    fun `plan carries fields and copies by value`() {
        val plan = Plan(setOf("m1", "m2"), "m2", 1700000000000L)
        assertEquals(setOf("m1", "m2"), plan.unreadIds)
        assertEquals("m2", plan.watermarkMessageId)
        assertEquals(1700000000000L, plan.watermarkTimestamp)
        assertEquals(Plan(setOf("m1"), "m1", 1L), plan.copy(unreadIds = setOf("m1"), watermarkMessageId = "m1", watermarkTimestamp = 1L))
    }

    @Test
    fun `seen set tracks adds and removals`() {
        val seen = SeenSet()
        assertTrue(seen.isEmpty())
        seen.add("m1")
        seen.addAll(listOf("m2", "m3"))
        assertTrue(seen.contains("m1"))
        assertTrue(seen.contains("m3"))
        seen.removeAll(listOf("m1", "m2"))
        assertFalse(seen.contains("m1"))
        assertTrue(seen.contains("m3"))
        seen.clearAll()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `seen set prunes to the most recent thousand past the threshold`() {
        val seen = SeenSet()
        (0..2000).forEach { seen.add("id$it") }
        assertEquals(1000, seen.snapshot().size)
        assertTrue(seen.contains("id2000"))
        assertFalse(seen.contains("id0"))
    }

    @Test
    fun `seen set snapshot is detached from later adds`() {
        val seen = SeenSet()
        seen.add("m1")
        val snapshot = seen.snapshot()
        seen.add("m2")
        assertFalse(snapshot.contains("m2"))
        assertTrue(seen.contains("m2"))
    }
}
