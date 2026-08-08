package com.maodouchat.data

import com.maodouchat.data.local.LikeQueryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class LikeQueryPolicyTest {

    @Test
    fun blankStaysBlank() {
        assertEquals("", LikeQueryPolicy.escapeForContains(""))
        assertEquals("", LikeQueryPolicy.escapeForContains("   "))
    }

    @Test
    fun plainTextUnchanged() {
        assertEquals("hello", LikeQueryPolicy.escapeForContains("hello"))
        assertEquals("拍一拍", LikeQueryPolicy.escapeForContains("拍一拍"))
    }

    @Test
    fun escapesWildcardsAndBackslash() {
        assertEquals("\\%", LikeQueryPolicy.escapeForContains("%"))
        assertEquals("\\_", LikeQueryPolicy.escapeForContains("_"))
        assertEquals("\\\\", LikeQueryPolicy.escapeForContains("\\"))
        assertEquals("a\\%b\\_c\\\\d", LikeQueryPolicy.escapeForContains("a%b_c\\d"))
    }
}
