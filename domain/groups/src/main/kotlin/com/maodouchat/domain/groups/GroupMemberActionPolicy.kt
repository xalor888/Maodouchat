package com.maodouchat.domain.groups

// 群成员管理操作可见性：纯角色判定；服务端再校验一次。
object GroupMemberActionPolicy {

    // 升/降管理员与转让群主：仅群主可对非群主成员操作。
    fun canManageMemberRole(isOwner: Boolean, isMe: Boolean, targetRole: String): Boolean =
        isOwner && !isMe && targetRole != "OWNER"

    // 禁言与移出：群主可管除群主外的所有人；管理员只能管普通成员。
    fun canModerateMember(isOwner: Boolean, isMe: Boolean, targetRole: String): Boolean =
        !isMe && targetRole != "OWNER" && (isOwner || targetRole != "ADMIN")
}
