package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.User
import com.maodouchat.core.model.GroupMemberUi
import com.maodouchat.domain.groups.GroupDetailSearch

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
        GroupDetailSearch.filterMembers(members, memberSearch)
    }
    val visibleMembers = remember(filteredMembers, membersExpanded) {
        GroupDetailSearch.visiblePage(filteredMembers, membersExpanded, GroupDetailSearch.MEMBER_PAGE_SIZE)
    }
    val filteredAuditLogs = remember(auditLogs, auditSearch) {
        GroupDetailSearch.filterAuditLogs(auditLogs, auditSearch)
    }
    val visibleAuditLogs = remember(filteredAuditLogs, auditExpanded) {
        GroupDetailSearch.visiblePage(filteredAuditLogs, auditExpanded, GroupDetailSearch.AUDIT_PAGE_SIZE)
    }
    val filteredCandidates = remember(candidates, candidateSearch) {
        GroupDetailSearch.filterCandidates(candidates, candidateSearch)
    }
    val visibleCandidates = remember(filteredCandidates, candidatesExpanded) {
        GroupDetailSearch.visiblePage(filteredCandidates, candidatesExpanded, GroupDetailSearch.CANDIDATE_PAGE_SIZE)
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
