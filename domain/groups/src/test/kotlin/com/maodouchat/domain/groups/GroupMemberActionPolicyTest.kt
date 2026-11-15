package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupMemberActionPolicyTest {

    @Test
    fun `owner can change the role of a member or an admin`() {
        assertTrue(GroupMemberActionPolicy.canManageMemberRole(isOwner = true, isMe = false, targetRole = "MEMBER"))
        assertTrue(GroupMemberActionPolicy.canManageMemberRole(isOwner = true, isMe = false, targetRole = "ADMIN"))
    }

    @Test
    fun `role change is refused for self the owner and non-owners`() {
        assertFalse(GroupMemberActionPolicy.canManageMemberRole(isOwner = true, isMe = true, targetRole = "MEMBER"))
        assertFalse(GroupMemberActionPolicy.canManageMemberRole(isOwner = true, isMe = false, targetRole = "OWNER"))
        assertFalse(GroupMemberActionPolicy.canManageMemberRole(isOwner = false, isMe = false, targetRole = "MEMBER"))
    }

    @Test
    fun `owner can moderate anyone except the owner`() {
        assertTrue(GroupMemberActionPolicy.canModerateMember(isOwner = true, isMe = false, targetRole = "MEMBER"))
        assertTrue(GroupMemberActionPolicy.canModerateMember(isOwner = true, isMe = false, targetRole = "ADMIN"))
        assertFalse(GroupMemberActionPolicy.canModerateMember(isOwner = true, isMe = false, targetRole = "OWNER"))
    }

    @Test
    fun `an admin can only moderate plain members and never self`() {
        assertTrue(GroupMemberActionPolicy.canModerateMember(isOwner = false, isMe = false, targetRole = "MEMBER"))
        assertFalse(GroupMemberActionPolicy.canModerateMember(isOwner = false, isMe = false, targetRole = "ADMIN"))
        assertFalse(GroupMemberActionPolicy.canModerateMember(isOwner = false, isMe = false, targetRole = "OWNER"))
        assertFalse(GroupMemberActionPolicy.canModerateMember(isOwner = true, isMe = true, targetRole = "MEMBER"))
    }
}
