package com.maodouchat.domain.groups

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.Test

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

    @Test
    fun `votedChoiceTitle picks the voted side`() {
        val left = PkListItem("p1", "left", "right", true, 3, 4, 7, "left")
        assertEquals("left", left.votedChoiceTitle)
        val right = PkListItem("p1", "left", "right", true, 3, 4, 7, "right")
        assertEquals("right", right.votedChoiceTitle)
    }

    @Test
    fun `votedChoiceTitle is null without a vote`() {
        val none = PkListItem("p1", "left", "right", true, 3, 4, 7, null)
        assertNull(none.votedChoiceTitle)
        val other = PkListItem("p1", "left", "right", true, 3, 4, 7, "other")
        assertNull(other.votedChoiceTitle)
    }
}
