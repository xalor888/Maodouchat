package com.maodouchat.domain.groups

// 群成员禁言徽标门禁：仅禁言且非群主时展示；role 保持原始字符串比较，与原调用点逐字一致。
object GroupMemberMuteBadgePolicy {

    fun shouldShowMute(isMuted: Boolean, role: String): Boolean = isMuted && role != "OWNER"
}
