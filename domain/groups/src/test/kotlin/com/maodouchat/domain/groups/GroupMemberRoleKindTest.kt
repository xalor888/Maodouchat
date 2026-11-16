package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupMemberRoleKindTest {

    @Test
    fun `owner and admin map one to one`() {
        assertEquals(GroupMemberRoleKind.OWNER, groupMemberRoleKind("OWNER"))
        assertEquals(GroupMemberRoleKind.ADMIN, groupMemberRoleKind("ADMIN"))
    }

    @Test
    fun `plain member maps to member`() {
        assertEquals(GroupMemberRoleKind.MEMBER, groupMemberRoleKind("MEMBER"))
    }

    @Test
    fun `unknown or blank role falls back to member like the old else branch`() {
        assertEquals(GroupMemberRoleKind.MEMBER, groupMemberRoleKind("MODERATOR"))
        assertEquals(GroupMemberRoleKind.MEMBER, groupMemberRoleKind(""))
    }
}
