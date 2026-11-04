package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CheckinRankItemTest {

    @Test
    fun `fields are carried`() {
        val item = CheckinRankItem("u1", 5, 20)
        assertEquals("u1", item.userId)
        assertEquals(5, item.streak)
        assertEquals(20, item.totalCount)
    }

    @Test
    fun `copy keeps other fields`() {
        val copied = CheckinRankItem("u1", 5, 20).copy(streak = 6)
        assertEquals("u1", copied.userId)
        assertEquals(6, copied.streak)
        assertEquals(20, copied.totalCount)
    }

    @Test
    fun `different users are not equal`() {
        assertNotEquals(CheckinRankItem("u1", 5, 20), CheckinRankItem("u2", 5, 20))
    }
}
