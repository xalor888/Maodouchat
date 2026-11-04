package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementModelsTest {

    @Test
    fun data_holdsFieldsWithAckedDefaultFalse() {
        val item = AnnouncementData("a1", "维护通知", "今晚停机", "MAINTENANCE", 1700000000000L, 1700003600000L, "ACTIVE")
        assertEquals("a1", item.id)
        assertEquals("维护通知", item.title)
        assertEquals("今晚停机", item.content)
        assertEquals("MAINTENANCE", item.level)
        assertEquals(1700000000000L, item.startsAt)
        assertEquals(1700003600000L, item.expiresAt)
        assertEquals("ACTIVE", item.status)
        assertFalse(item.acked)
    }

    @Test
    fun data_copyCanMarkAcked() {
        val item = AnnouncementData("a2", "T", "C", "EMERGENCY", 1L, 2L, "ACTIVE")
        assertTrue(item.copy(acked = true).acked)
        assertFalse(item.acked)
    }
}
