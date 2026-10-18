package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maodouchat.data.model.User
import com.maodouchat.group.GroupMemberUi
import com.maodouchat.network.GroupAuditLogDto
import com.maodouchat.ui.screen.chatdetail.group.groupAuditActionSearchTokens
import com.maodouchat.ui.screen.chatdetail.group.roleRank

internal const val MEMBER_PAGE_SIZE = 100
internal const val AUDIT_PAGE_SIZE = 80
internal const val CANDIDATE_PAGE_SIZE = 32

// 群详情页搜索过滤派生量：成员 / 审计 / 可添加联系人三条「过滤→分页」链。
// 原在 GroupDetailScreen 内联声明，纯搬移不改判断。
internal class GroupDetailSearchDerived(
    val filteredMembers: List<GroupMemberUi>,
    val visibleMembers: List<GroupMemberUi>,
    val filteredAuditLogs: List<GroupAuditLogDto>,
    val visibleAuditLogs: List<GroupAuditLogDto>,
    val filteredCandidates: List<User>,
    val visibleCandidates: List<User>,
)

@Composable
internal fun rememberGroupDetailSearchDerived(
    members: List<GroupMemberUi>,
    memberSearch: String,
    membersExpanded: Boolean,
    auditLogs: List<GroupAuditLogDto>,
    auditSearch: String,
    auditExpanded: Boolean,
    candidates: List<User>,
    candidateSearch: String,
    candidatesExpanded: Boolean,
): GroupDetailSearchDerived {
    val filteredMembers = remember(members, memberSearch) {
        val query = memberSearch.trim()
        val base = if (query.isBlank()) members else members.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.title.orEmpty().contains(query, ignoreCase = true) ||
                it.userId.contains(query, ignoreCase = true)
        }
        base.sortedWith(
            compareBy<GroupMemberUi> { roleRank(it.role) }
                .thenByDescending { it.isOnline }
                .thenBy { it.displayName.lowercase() }
        )
    }
    val visibleMembers = remember(filteredMembers, membersExpanded) {
        if (membersExpanded || filteredMembers.size <= MEMBER_PAGE_SIZE) {
            filteredMembers
        } else {
            filteredMembers.take(MEMBER_PAGE_SIZE)
        }
    }
    val filteredAuditLogs = remember(auditLogs, auditSearch) {
        val query = auditSearch.trim()
        if (query.isBlank()) {
            auditLogs
        } else {
            auditLogs.filter { audit ->
                audit.actorName.contains(query, ignoreCase = true) ||
                    audit.actorId.contains(query, ignoreCase = true) ||
                    audit.action.contains(query, ignoreCase = true) ||
                    audit.targetUserName.orEmpty().contains(query, ignoreCase = true) ||
                    audit.targetUserId.orEmpty().contains(query, ignoreCase = true) ||
                    groupAuditActionSearchTokens(audit.action).any { token ->
                        token.contains(query, ignoreCase = true)
                    }
            }
        }
    }
    val visibleAuditLogs = remember(filteredAuditLogs, auditExpanded) {
        if (auditExpanded || filteredAuditLogs.size <= AUDIT_PAGE_SIZE) {
            filteredAuditLogs
        } else {
            filteredAuditLogs.take(AUDIT_PAGE_SIZE)
        }
    }
    val filteredCandidates = remember(candidates, candidateSearch) {
        val query = candidateSearch.trim()
        if (query.isBlank()) {
            candidates
        } else {
            candidates.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true) ||
                it.id.contains(query, ignoreCase = true)
            }
        }
    }
    val visibleCandidates = remember(filteredCandidates, candidatesExpanded) {
        if (candidatesExpanded || filteredCandidates.size <= CANDIDATE_PAGE_SIZE) {
            filteredCandidates
        } else {
            filteredCandidates.take(CANDIDATE_PAGE_SIZE)
        }
    }
    return GroupDetailSearchDerived(
        filteredMembers = filteredMembers,
        visibleMembers = visibleMembers,
        filteredAuditLogs = filteredAuditLogs,
        visibleAuditLogs = visibleAuditLogs,
        filteredCandidates = filteredCandidates,
        visibleCandidates = visibleCandidates,
    )
}
