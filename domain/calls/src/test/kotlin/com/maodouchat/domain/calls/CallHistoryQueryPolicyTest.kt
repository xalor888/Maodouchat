package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class CallHistoryQueryPolicyTest {

    @Test
    fun `empty query stays empty`() {
        assertEquals("", CallHistoryQueryPolicy.filterQuery(""))
    }

    @Test
    fun `short query passes through`() {
        assertEquals("abc", CallHistoryQueryPolicy.filterQuery("abc"))
    }

    @Test
    fun `exact boundary passes through`() {
        val q = "x".repeat(100)
        assertEquals(q, CallHistoryQueryPolicy.filterQuery(q))
    }

    @Test
    fun `overlong query truncates at 100`() {
        assertEquals("x".repeat(100), CallHistoryQueryPolicy.filterQuery("x".repeat(101)))
    }

    @Test
    fun `overlong chinese query truncates at 100 chars`() {
        val out = CallHistoryQueryPolicy.filterQuery("中".repeat(105))
        assertEquals(100, out.length)
        assertEquals("中".repeat(100), out)
    }
}
