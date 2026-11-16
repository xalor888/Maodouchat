package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupMemberMuteBadgePolicyTest {

    @Test
    fun `shows badge for muted non-owner`() {
        assertTrue(GroupMemberMuteBadgePolicy.shouldShowMute(true, "MEMBER"))
        assertTrue(GroupMemberMuteBadgePolicy.shouldShowMute(true, "ADMIN"))
    }

    @Test
    fun `hides badge for owner or unmuted`() {
        assertFalse(GroupMemberMuteBadgePolicy.shouldShowMute(true, "OWNER"))
        assertFalse(GroupMemberMuteBadgePolicy.shouldShowMute(false, "MEMBER"))
        assertFalse(GroupMemberMuteBadgePolicy.shouldShowMute(false, "OWNER"))
    }
}
