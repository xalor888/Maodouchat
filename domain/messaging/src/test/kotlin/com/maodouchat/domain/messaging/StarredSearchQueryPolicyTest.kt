package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class StarredSearchQueryPolicyTest {

    @Test
    fun `empty query stays empty`() {
        assertEquals("", StarredSearchQueryPolicy.filterQuery(""))
    }

    @Test
    fun `short query passes through`() {
        assertEquals("abc", StarredSearchQueryPolicy.filterQuery("abc"))
    }

    @Test
    fun `exact boundary passes through`() {
        val q = "x".repeat(160)
        assertEquals(q, StarredSearchQueryPolicy.filterQuery(q))
    }

    @Test
    fun `overlong query truncates at 160`() {
        assertEquals("x".repeat(160), StarredSearchQueryPolicy.filterQuery("x".repeat(161)))
    }

    @Test
    fun `overlong chinese query truncates at 160 chars`() {
        val out = StarredSearchQueryPolicy.filterQuery("中".repeat(165))
        assertEquals(160, out.length)
        assertEquals("中".repeat(160), out)
    }
}
