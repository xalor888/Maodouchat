package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupPkValidationPolicyTest {

    @Test
    fun `title accepts one hundred twenty chars and rejects longer`() {
        assertTrue(GroupPkValidationPolicy.isValidTitle("x".repeat(120)))
        assertFalse(GroupPkValidationPolicy.isValidTitle("x".repeat(121)))
    }

    @Test
    fun `title rejects blank and counts after trimming`() {
        assertFalse(GroupPkValidationPolicy.isValidTitle(""))
        assertFalse(GroupPkValidationPolicy.isValidTitle("   "))
        assertTrue(GroupPkValidationPolicy.isValidTitle("  " + "x".repeat(120) + "  "))
        assertFalse(GroupPkValidationPolicy.isValidTitle("  " + "x".repeat(121) + "  "))
    }

    @Test
    fun `titles require both sides valid`() {
        assertTrue(GroupPkValidationPolicy.isValidTitles("a", "b"))
        assertFalse(GroupPkValidationPolicy.isValidTitles("", "b"))
        assertFalse(GroupPkValidationPolicy.isValidTitles("a", ""))
        assertFalse(GroupPkValidationPolicy.isValidTitles("x".repeat(121), "b"))
        assertFalse(GroupPkValidationPolicy.isValidTitles("a", "x".repeat(121)))
    }
}
