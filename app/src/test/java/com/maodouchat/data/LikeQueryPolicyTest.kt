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

    @Test
    fun prefixEscapeKeepsBlankButFoldsWildcards() {
        // 9.236：前缀转义不归空（空白也是合法前缀内容），通配符同样转义
        assertEquals("", LikeQueryPolicy.escapeForPrefix(""))
        assertEquals("  ", LikeQueryPolicy.escapeForPrefix("  "))
        assertEquals("user\\_id", LikeQueryPolicy.escapeForPrefix("user_id"))
        assertEquals("a\\%b", LikeQueryPolicy.escapeForPrefix("a%b"))
        // 普通 accountId（十六进制/字母数字）转义无变化，新旧键字面量兼容
        assertEquals("9f2c4ab1", LikeQueryPolicy.escapeForPrefix("9f2c4ab1"))
    }
}
