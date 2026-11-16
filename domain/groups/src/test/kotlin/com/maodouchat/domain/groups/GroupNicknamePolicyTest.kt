package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupNicknamePolicyTest {

    @Test
    fun `filterInput truncates overlong input to 100 chars`() {
        assertEquals("a".repeat(100), GroupNicknamePolicy.filterInput("a".repeat(101)))
        assertEquals("a".repeat(100), GroupNicknamePolicy.filterInput("a".repeat(300)))
    }

    @Test
    fun `filterInput keeps short input unchanged`() {
        assertEquals("abc", GroupNicknamePolicy.filterInput("abc"))
        assertEquals("", GroupNicknamePolicy.filterInput(""))
        assertEquals("a".repeat(100), GroupNicknamePolicy.filterInput("a".repeat(100)))
    }

    @Test
    fun `max length is 100`() {
        assertEquals(100, GroupNicknamePolicy.MAX_LENGTH)
    }
}
