package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LikeQueryPolicyTest {

    @Test
    fun `contains escapes wildcards and backslash`() {
        assertEquals("\\%", LikeQueryPolicy.escapeForContains("%"))
        assertEquals("a\\%b\\_c\\\\d", LikeQueryPolicy.escapeForContains("a%b_c\\d"))
    }

    @Test
    fun `contains returns blank for blank input`() {
        assertEquals("", LikeQueryPolicy.escapeForContains("   "))
        assertEquals("", LikeQueryPolicy.escapeForContains("\t"))
    }

    @Test
    fun `prefix keeps blank but still escapes wildcards`() {
        assertEquals("  ", LikeQueryPolicy.escapeForPrefix("  "))
        assertEquals("user\\_id", LikeQueryPolicy.escapeForPrefix("user_id"))
    }

    @Test
    fun `escape char is backslash`() {
        assertEquals('\\', LikeQueryPolicy.ESCAPE_CHAR)
    }
}
