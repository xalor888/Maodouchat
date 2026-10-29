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
    @Test
    fun containsKeepsInnerAndEdgeSpacesForNonBlankInput() {
        assertEquals("a b", LikeQueryPolicy.escapeForContains("a b"))
        assertEquals(" hello ", LikeQueryPolicy.escapeForContains(" hello "))
    }

    @Test
    fun containsEscapesRepeatedWildcards() {
        assertEquals("\\%\\_\\%", LikeQueryPolicy.escapeForContains("%_%"))
    }

    @Test
    fun prefixEscapesBackslash() {
        assertEquals("a\\\\b", LikeQueryPolicy.escapeForPrefix("a\\b"))
    }

    @Test
    fun prefixKeepsWhitespaceVerbatim() {
        assertEquals(" a b ", LikeQueryPolicy.escapeForPrefix(" a b "))
    }

    @Test
    fun escapeCharIsBackslashAndBothEscapersAgreeOnNonBlank() {
        assertEquals('\\', LikeQueryPolicy.ESCAPE_CHAR)
        val sample = "a%b_c"
        assertEquals(
            LikeQueryPolicy.escapeForContains(sample),
            LikeQueryPolicy.escapeForPrefix(sample),
        )
    }

    @Test
    fun containsBlankWhitespaceVariantsReturnEmpty() {
        assertEquals("", LikeQueryPolicy.escapeForContains("\t"))
        assertEquals("", LikeQueryPolicy.escapeForContains("\n"))
        assertEquals("", LikeQueryPolicy.escapeForContains(" \t\n "))
    }

    @Test
    fun containsLeavesOtherPunctuationUntouched() {
        assertEquals("a-b!c@d", LikeQueryPolicy.escapeForContains("a-b!c@d"))
    }

    @Test
    fun prefixEscapesRepeatedWildcards() {
        assertEquals("\\%\\_\\%", LikeQueryPolicy.escapeForPrefix("%_%"))
    }

    @Test
    fun containsDoubleEscapesAlreadyEscapedInput() {
        assertEquals("\\\\\\%", LikeQueryPolicy.escapeForContains("\\%"))
    }

    @Test
    fun prefixKeepsUnicodeVerbatimExceptSpecials() {
        assertEquals("用户\\_123\\%", LikeQueryPolicy.escapeForPrefix("用户_123%"))
    }


    @Test
    fun nbspIsBlankForContainsButVerbatimForPrefix() {
        assertEquals("", LikeQueryPolicy.escapeForContains("\u00A0"))
        assertEquals("\u00A0", LikeQueryPolicy.escapeForPrefix("\u00A0"))
    }

    @Test
    fun containsAndPrefixSplitOnBlankOnly() {
        for (blank in listOf("\t", "   ", " \t\n ")) {
            assertEquals("", LikeQueryPolicy.escapeForContains(blank))
            assertEquals(blank, LikeQueryPolicy.escapeForPrefix(blank))
        }
    }

    @Test
    fun prefixDoubleEscapesAlreadyEscapedInput() {
        assertEquals("\\\\\\%", LikeQueryPolicy.escapeForPrefix("\\%"))
    }

    @Test
    fun containsLeavesBracketsQuotesAndColonUntouched() {
        assertEquals("a[b]c'd:e;f", LikeQueryPolicy.escapeForContains("a[b]c'd:e;f"))
    }

    @Test
    fun doubleEscapeIsNotIdempotent() {
        assertEquals(
            "a\\\\\\%b\\\\\\_c",
            LikeQueryPolicy.escapeForContains(LikeQueryPolicy.escapeForContains("a%b_c"))
        )
    }
}
