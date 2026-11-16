package com.maodouchat.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupAuditActionLabelTest {

    @Test
    fun `every known action maps to its kind`() {
        val known = mapOf(
            "MEMBER_ADDED" to GroupAuditActionLabelKind.MemberAdded,
            "MEMBER_JOINED" to GroupAuditActionLabelKind.MemberJoined,
            "MEMBER_REMOVED" to GroupAuditActionLabelKind.MemberRemoved,
            "MEMBER_PROMOTED" to GroupAuditActionLabelKind.MemberPromoted,
            "MEMBER_DEMOTED" to GroupAuditActionLabelKind.MemberDemoted,
            "MEMBER_MUTED" to GroupAuditActionLabelKind.MemberMuted,
            "MEMBER_UNMUTED" to GroupAuditActionLabelKind.MemberUnmuted,
            "GROUP_RENAMED" to GroupAuditActionLabelKind.GroupRenamed,
            "ANNOUNCEMENT_UPDATED" to GroupAuditActionLabelKind.AnnouncementUpdated,
            "AVATAR_UPDATED" to GroupAuditActionLabelKind.AvatarUpdated,
            "INVITE_ROTATED" to GroupAuditActionLabelKind.Invite,
            "INVITE_CONFIGURED" to GroupAuditActionLabelKind.Invite,
            "TITLE_UPDATED" to GroupAuditActionLabelKind.TitleUpdated,
            "NICKNAME_UPDATED" to GroupAuditActionLabelKind.NicknameUpdated,
            "MEMBER_LEFT" to GroupAuditActionLabelKind.MemberLeft,
            "OWNERSHIP_TRANSFERRED" to GroupAuditActionLabelKind.OwnershipTransferred,
        )
        known.forEach { (action, expected) ->
            assertEquals(expected, groupAuditActionLabelKind(action))
        }
    }

    @Test
    fun `unknown action is other`() {
        assertEquals(GroupAuditActionLabelKind.Other, groupAuditActionLabelKind("MEMBER_BANNED"))
        assertEquals(GroupAuditActionLabelKind.Other, groupAuditActionLabelKind(""))
    }
}
