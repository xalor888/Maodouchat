package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupMemberUi
import com.maodouchat.core.model.User

// 群详情页成员 / 审计 / 可添加联系人三条「过滤→分页」链：纯搬移自 app 侧 GroupDetailSearchDerived。
object GroupDetailSearch {
    const val MEMBER_PAGE_SIZE = 100
    const val AUDIT_PAGE_SIZE = 80
    const val CANDIDATE_PAGE_SIZE = 32

    fun roleRank(role: String): Int = when (role) {
        "OWNER" -> 0
        "ADMIN" -> 1
        else -> 2
    }

    fun filterMembers(members: List<GroupMemberUi>, query: String): List<GroupMemberUi> {
        val q = query.trim()
        val base = if (q.isBlank()) members else members.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true) ||
                it.title.orEmpty().contains(q, ignoreCase = true) ||
                it.userId.contains(q, ignoreCase = true)
        }
        return base.sortedWith(
            compareBy<GroupMemberUi> { roleRank(it.role) }
                .thenByDescending { it.isOnline }
                .thenBy { it.displayName.lowercase() }
        )
    }

    fun filterAuditLogs(auditLogs: List<GroupAuditLogDto>, query: String): List<GroupAuditLogDto> {
        val q = query.trim()
        if (q.isBlank()) return auditLogs
        return auditLogs.filter { audit ->
            audit.actorName.contains(q, ignoreCase = true) ||
                audit.actorId.contains(q, ignoreCase = true) ||
                audit.action.contains(q, ignoreCase = true) ||
                audit.targetUserName.orEmpty().contains(q, ignoreCase = true) ||
                audit.targetUserId.orEmpty().contains(q, ignoreCase = true) ||
                groupAuditActionSearchTokens(audit.action).any { token ->
                    token.contains(q, ignoreCase = true)
                }
        }
    }

    fun filterCandidates(candidates: List<User>, query: String): List<User> {
        val q = query.trim()
        if (q.isBlank()) return candidates
        return candidates.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true) ||
                it.id.contains(q, ignoreCase = true)
        }
    }

    fun <T> visiblePage(filtered: List<T>, expanded: Boolean, pageSize: Int): List<T> =
        if (expanded || filtered.size <= pageSize) filtered else filtered.take(pageSize)

    fun groupAuditActionSearchTokens(action: String): List<String> = when (action) {
        "MEMBER_ADDED" -> listOf("添加", "成员", "added", "member", "add")
        "MEMBER_JOINED" -> listOf("加入", "邀请", "joined", "invite", "join")
        "MEMBER_REMOVED" -> listOf("移除", "踢出", "removed", "remove", "kick")
        "MEMBER_PROMOTED" -> listOf("管理员", "提升", "admin", "promoted", "promote")
        "MEMBER_DEMOTED" -> listOf("取消管理员", "降级", "demoted", "demote")
        "MEMBER_MUTED" -> listOf("禁言", "muted", "mute")
        "MEMBER_UNMUTED" -> listOf("解禁", "解除禁言", "unmuted", "unmute")
        "GROUP_RENAMED" -> listOf("群名", "改名", "rename", "renamed")
        "ANNOUNCEMENT_UPDATED" -> listOf("公告", "announcement")
        "AVATAR_UPDATED" -> listOf("头像", "avatar")
        "INVITE_ROTATED", "INVITE_CONFIGURED" -> listOf("邀请", "链接", "invite", "link")
        "TITLE_UPDATED" -> listOf("头衔", "title")
        "NICKNAME_UPDATED" -> listOf("昵称", "nickname")
        "MEMBER_LEFT" -> listOf("退群", "退出", "left", "leave")
        "OWNERSHIP_TRANSFERRED" -> listOf("转让", "群主", "owner", "transfer")
        else -> listOf("操作", "activity", "group")
    }
}
