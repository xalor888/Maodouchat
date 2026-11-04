package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PkListItemTest {

    @Test
    fun `fields are carried`() {
        val item = PkListItem("p1", "left", "right", true, 3, 4, 7, "left")
        assertEquals("p1", item.id)
        assertEquals("left", item.leftTitle)
        assertEquals("right", item.rightTitle)
        assertEquals(true, item.active)
        assertEquals(3, item.leftCount)
        assertEquals(4, item.rightCount)
        assertEquals(7, item.totalVoters)
        assertEquals("left", item.myChoice)
    }

    @Test
    fun `different ids are not equal`() {
        val a = PkListItem("p1", "left", "right", true, 3, 4, 7, null)
        val b = PkListItem("p2", "left", "right", true, 3, 4, 7, null)
        assertNotEquals(a, b)
    }
}
