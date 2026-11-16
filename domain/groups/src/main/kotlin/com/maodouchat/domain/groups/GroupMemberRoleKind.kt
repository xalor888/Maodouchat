package com.maodouchat.domain.groups

// 群成员角色文案种类；R.string 映射留在 app 侧。
enum class GroupMemberRoleKind {
    OWNER,
    ADMIN,
    MEMBER,
}

fun groupMemberRoleKind(role: String): GroupMemberRoleKind =
    when (role) {
        "OWNER" -> GroupMemberRoleKind.OWNER
        "ADMIN" -> GroupMemberRoleKind.ADMIN
        else -> GroupMemberRoleKind.MEMBER
    }
