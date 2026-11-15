package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadBadgePolicyTest {

    @Test
    fun `small counts shown as-is`() {
        assertEquals("0", UnreadBadgePolicy.format(0))
        assertEquals("1", UnreadBadgePolicy.format(1))
        assertEquals("99", UnreadBadgePolicy.format(99))
    }

    @Test
    fun `over 99 shown as 99 plus`() {
        assertEquals("99+", UnreadBadgePolicy.format(100))
        assertEquals("99+", UnreadBadgePolicy.format(1234))
    }

    @Test
    fun `negative counts shown as-is`() {
        assertEquals("-1", UnreadBadgePolicy.format(-1))
    }
}
