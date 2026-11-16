package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadReceiptQueryPolicyTest {

    @Test
    fun `empty query stays empty`() {
        assertEquals("", ReadReceiptQueryPolicy.filterQuery(""))
    }

    @Test
    fun `short query passes through`() {
        assertEquals("abc", ReadReceiptQueryPolicy.filterQuery("abc"))
    }

    @Test
    fun `exact boundary passes through`() {
        val q = "x".repeat(100)
        assertEquals(q, ReadReceiptQueryPolicy.filterQuery(q))
    }

    @Test
    fun `overlong query truncates at 100`() {
        assertEquals("x".repeat(100), ReadReceiptQueryPolicy.filterQuery("x".repeat(101)))
    }

    @Test
    fun `overlong chinese query truncates at 100 chars`() {
        val out = ReadReceiptQueryPolicy.filterQuery("中".repeat(105))
        assertEquals(100, out.length)
        assertEquals("中".repeat(100), out)
    }
}
