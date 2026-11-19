package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.Test

class FindingTest {

    @Test
    fun `fields are kept verbatim`() {
        val finding = Finding("SUSPICIOUS_LINK", "example.tk")
        assertEquals("SUSPICIOUS_LINK", finding.code)
        assertEquals("example.tk", finding.matched)
    }

    @Test
    fun `matched defaults to null`() {
        assertNull(Finding("PAYMENT_INDUCEMENT").matched)
    }

    @Test
    fun `equality is by value`() {
        assertEquals(Finding("A", "x"), Finding("A", "x"))
        assertNotEquals(Finding("A", "x"), Finding("A", "y"))
        assertNotEquals(Finding("A"), Finding("B"))
    }
}
