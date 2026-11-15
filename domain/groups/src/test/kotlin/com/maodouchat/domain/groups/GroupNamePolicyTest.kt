package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupNamePolicyTest {

    @Test
    fun `filterInput truncates overlong input to fifty chars`() {
        assertEquals("a".repeat(50), GroupNamePolicy.filterInput("a".repeat(60)))
        assertEquals("abc", GroupNamePolicy.filterInput("abc"))
        assertEquals("", GroupNamePolicy.filterInput(""))
    }

    @Test
    fun `filterInput keeps surrounding spaces for save time handling`() {
        assertEquals("  ab  ", GroupNamePolicy.filterInput("  ab  "))
    }

    @Test
    fun `normalize trims and truncates`() {
        assertEquals("a".repeat(50), GroupNamePolicy.normalize("  " + "a".repeat(60) + "  "))
        assertEquals("abc", GroupNamePolicy.normalize("  abc  "))
    }

    @Test
    fun `validate rejects blank names`() {
        assertEquals(GroupNamePolicy.Validation.Blank, GroupNamePolicy.validate(""))
        assertEquals(GroupNamePolicy.Validation.Blank, GroupNamePolicy.validate("   "))
    }

    @Test
    fun `validate rejects names over fifty chars after trimming`() {
        assertEquals(GroupNamePolicy.Validation.TooLong, GroupNamePolicy.validate("a".repeat(51)))
        assertEquals(GroupNamePolicy.Validation.TooLong, GroupNamePolicy.validate("  " + "a".repeat(51) + "  "))
    }

    @Test
    fun `validate accepts fifty chars and trims before counting`() {
        assertEquals(GroupNamePolicy.Validation.Valid, GroupNamePolicy.validate("a".repeat(50)))
        assertEquals(GroupNamePolicy.Validation.Valid, GroupNamePolicy.validate("  " + "a".repeat(50) + "  "))
    }
}
