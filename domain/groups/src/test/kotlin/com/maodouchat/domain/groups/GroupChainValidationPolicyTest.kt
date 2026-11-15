package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupChainValidationPolicyTest {

    @Test
    fun `title accepts two hundred chars and rejects longer`() {
        assertTrue(GroupChainValidationPolicy.isValidTitle("x".repeat(200)))
        assertFalse(GroupChainValidationPolicy.isValidTitle("x".repeat(201)))
    }

    @Test
    fun `title rejects blank and counts after trimming`() {
        assertFalse(GroupChainValidationPolicy.isValidTitle(""))
        assertFalse(GroupChainValidationPolicy.isValidTitle("   "))
        assertTrue(GroupChainValidationPolicy.isValidTitle("  " + "x".repeat(200) + "  "))
        assertFalse(GroupChainValidationPolicy.isValidTitle("  " + "x".repeat(201) + "  "))
    }

    @Test
    fun `topic accepts empty and five hundred chars`() {
        assertTrue(GroupChainValidationPolicy.isValidTopic(""))
        assertTrue(GroupChainValidationPolicy.isValidTopic("   "))
        assertTrue(GroupChainValidationPolicy.isValidTopic("x".repeat(500)))
        assertFalse(GroupChainValidationPolicy.isValidTopic("x".repeat(501)))
    }

    @Test
    fun `entry content rejects blank and counts after trimming`() {
        assertFalse(GroupChainValidationPolicy.isValidEntryContent(""))
        assertFalse(GroupChainValidationPolicy.isValidEntryContent("   "))
        assertTrue(GroupChainValidationPolicy.isValidEntryContent("x".repeat(500)))
        assertFalse(GroupChainValidationPolicy.isValidEntryContent("x".repeat(501)))
    }

    @Test
    fun `canJoin allows only not joined and active`() {
        assertTrue(GroupChainValidationPolicy.canJoin(myJoined = false, active = true))
        assertFalse(GroupChainValidationPolicy.canJoin(myJoined = true, active = true))
        assertFalse(GroupChainValidationPolicy.canJoin(myJoined = false, active = false))
        assertFalse(GroupChainValidationPolicy.canJoin(myJoined = true, active = false))
    }
}
