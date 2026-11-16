package com.maodouchat.domain.groups

// 群审计动作→文案种类；R.string 映射留在 app 侧。
enum class GroupAuditActionLabelKind {
    MemberAdded,
    MemberJoined,
    MemberRemoved,
    MemberPromoted,
    MemberDemoted,
    MemberMuted,
    MemberUnmuted,
    GroupRenamed,
    AnnouncementUpdated,
    AvatarUpdated,
    Invite,
    TitleUpdated,
    NicknameUpdated,
    MemberLeft,
    OwnershipTransferred,
    Other,
}

fun groupAuditActionLabelKind(action: String): GroupAuditActionLabelKind = when (action) {
    "MEMBER_ADDED" -> GroupAuditActionLabelKind.MemberAdded
    "MEMBER_JOINED" -> GroupAuditActionLabelKind.MemberJoined
    "MEMBER_REMOVED" -> GroupAuditActionLabelKind.MemberRemoved
    "MEMBER_PROMOTED" -> GroupAuditActionLabelKind.MemberPromoted
    "MEMBER_DEMOTED" -> GroupAuditActionLabelKind.MemberDemoted
    "MEMBER_MUTED" -> GroupAuditActionLabelKind.MemberMuted
    "MEMBER_UNMUTED" -> GroupAuditActionLabelKind.MemberUnmuted
    "GROUP_RENAMED" -> GroupAuditActionLabelKind.GroupRenamed
    "ANNOUNCEMENT_UPDATED" -> GroupAuditActionLabelKind.AnnouncementUpdated
    "AVATAR_UPDATED" -> GroupAuditActionLabelKind.AvatarUpdated
    "INVITE_ROTATED", "INVITE_CONFIGURED" -> GroupAuditActionLabelKind.Invite
    "TITLE_UPDATED" -> GroupAuditActionLabelKind.TitleUpdated
    "NICKNAME_UPDATED" -> GroupAuditActionLabelKind.NicknameUpdated
    "MEMBER_LEFT" -> GroupAuditActionLabelKind.MemberLeft
    "OWNERSHIP_TRANSFERRED" -> GroupAuditActionLabelKind.OwnershipTransferred
    else -> GroupAuditActionLabelKind.Other
}
