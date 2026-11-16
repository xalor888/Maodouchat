package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCenterQueryPolicyTest {

    @Test
    fun `empty query stays empty`() {
        assertEquals("", MediaCenterQueryPolicy.filterQuery(""))
    }

    @Test
    fun `short query passes through`() {
        assertEquals("abc", MediaCenterQueryPolicy.filterQuery("abc"))
    }

    @Test
    fun `exact boundary passes through`() {
        val q = "x".repeat(200)
        assertEquals(q, MediaCenterQueryPolicy.filterQuery(q))
    }

    @Test
    fun `overlong query truncates at 200`() {
        assertEquals("x".repeat(200), MediaCenterQueryPolicy.filterQuery("x".repeat(201)))
    }

    @Test
    fun `overlong chinese query truncates at 200 chars`() {
        val out = MediaCenterQueryPolicy.filterQuery("中".repeat(205))
        assertEquals(200, out.length)
        assertEquals("中".repeat(200), out)
    }
}
