package com.maodouchat.domain.contacts

import com.maodouchat.domain.contacts.UsernamePolicy.Validation
import org.junit.Assert.assertEquals
import org.junit.Test

class UsernamePolicyTest {

    @Test
    fun `filter keeps only allowed chars and lowercases`() {
        assertEquals("ada_1-x", UsernamePolicy.filterInput("Ada_1-X"))
        assertEquals("ada", UsernamePolicy.filterInput("@Ada!#%"))
        assertEquals("", UsernamePolicy.filterInput("!!!"))
    }

    @Test
    fun `filter truncates to fifty chars before lowercasing`() {
        val long = "A".repeat(60)
        assertEquals("a".repeat(50), UsernamePolicy.filterInput(long))
    }

    @Test
    fun `filter drops at sign before truncation so it never occupies a slot`() {
        // 过滤先于截断：@ 在 filter 步已被去掉，不占 50 字符名额，故 60 个 A 仍留 50 个。
        assertEquals("a".repeat(50), UsernamePolicy.filterInput("@" + "A".repeat(60)))
    }

    @Test
    fun `validate rejects shorter than three chars`() {
        assertEquals(Validation.TooShort, UsernamePolicy.validate(""))
        assertEquals(Validation.TooShort, UsernamePolicy.validate("ab"))
        assertEquals(Validation.Valid, UsernamePolicy.validate("abc"))
    }

    @Test
    fun `validate rejects disallowed chars after the length check`() {
        assertEquals(Validation.InvalidCharacters, UsernamePolicy.validate("ab!"))
        assertEquals(Validation.InvalidCharacters, UsernamePolicy.validate("a b"))
        assertEquals(Validation.Valid, UsernamePolicy.validate("a-b_c9"))
    }

    @Test
    fun `length check wins over charset check`() {
        assertEquals(Validation.TooShort, UsernamePolicy.validate("a!"))
    }
}
