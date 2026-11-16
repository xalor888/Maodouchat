package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiTaskQueryPolicyTest {

    @Test
    fun `empty query stays empty`() {
        assertEquals("", AiTaskQueryPolicy.filterQuery(""))
    }

    @Test
    fun `short query passes through`() {
        assertEquals("abc", AiTaskQueryPolicy.filterQuery("abc"))
    }

    @Test
    fun `exact boundary passes through`() {
        val q = "x".repeat(160)
        assertEquals(q, AiTaskQueryPolicy.filterQuery(q))
    }

    @Test
    fun `overlong query truncates at 160`() {
        assertEquals("x".repeat(160), AiTaskQueryPolicy.filterQuery("x".repeat(161)))
    }

    @Test
    fun `overlong chinese query truncates at 160 chars`() {
        val out = AiTaskQueryPolicy.filterQuery("中".repeat(165))
        assertEquals(160, out.length)
        assertEquals("中".repeat(160), out)
    }
}
