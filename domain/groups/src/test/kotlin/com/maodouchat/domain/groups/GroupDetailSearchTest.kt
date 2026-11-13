package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupMemberUi
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDetailSearchTest {

    private fun member(
        userId: String,
        name: String,
        role: String = "MEMBER",
        isOnline: Boolean = false,
        title: String? = null,
        groupNickname: String? = null,
    ) = GroupMemberUi(
        userId = userId,
        name = name,
        role = role,
        title = title,
        groupNickname = groupNickname,
        isOnline = isOnline,
    )

    private fun audit(
        id: String,
        action: String,
        actorName: String = "actor",
        actorId: String = "a1",
        targetUserName: String? = null,
        targetUserId: String? = null,
    ) = GroupAuditLogDto(
        id = id,
        actorId = actorId,
        actorName = actorName,
        action = action,
        targetUserId = targetUserId,
        targetUserName = targetUserName,
        createdAt = 0L,
    )

    @Test
    fun `roleRank orders owner admin member unknown`() {
        assertEquals(0, GroupDetailSearch.roleRank("OWNER"))
        assertEquals(1, GroupDetailSearch.roleRank("ADMIN"))
        assertEquals(2, GroupDetailSearch.roleRank("MEMBER"))
        assertEquals(2, GroupDetailSearch.roleRank("SOMETHING_ELSE"))
    }

    @Test
    fun `blank member query keeps all but still sorts by role online name`() {
        val members = listOf(
            member("u3", "Charlie"),
            member("u1", "Alice", role = "ADMIN", isOnline = true),
            member("u2", "Bob", role = "OWNER"),
        )
        val result = GroupDetailSearch.filterMembers(members, "   ")
        assertEquals(listOf("u2", "u1", "u3"), result.map { it.userId })
    }

    @Test
    fun `member query matches displayName case-insensitively`() {
        val members = listOf(
            member("u1", "Alice", groupNickname = "阿狸"),
            member("u2", "Bob"),
        )
        assertEquals(listOf("u1"), GroupDetailSearch.filterMembers(members, "阿狸").map { it.userId })
        assertEquals(listOf("u1"), GroupDetailSearch.filterMembers(members, "ALICE").map { it.userId })
    }

    @Test
    fun `member query matches name title and userId`() {
        val members = listOf(
            member("u1", "Alice", title = "大管家"),
            member("u2", "Bob"),
            member("xyz-9", "Carol"),
        )
        assertEquals(listOf("u1"), GroupDetailSearch.filterMembers(members, "管家").map { it.userId })
        assertEquals(listOf("xyz-9"), GroupDetailSearch.filterMembers(members, "xyz").map { it.userId })
        assertEquals(listOf("u2"), GroupDetailSearch.filterMembers(members, "bob").map { it.userId })
    }

    @Test
    fun `member query is trimmed and empty after trim matches all`() {
        val members = listOf(member("u1", "Alice"), member("u2", "Bob"))
        assertEquals(listOf("u1"), GroupDetailSearch.filterMembers(members, "  alice  ").map { it.userId })
        assertEquals(2, GroupDetailSearch.filterMembers(members, " ").size)
    }

    @Test
    fun `members sort online before offline within same role then by lowercase name`() {
        val members = listOf(
            member("u3", "zoe"),
            member("u1", "Amy", isOnline = true),
            member("u2", "bob", isOnline = true),
        )
        assertEquals(listOf("u1", "u2", "u3"), GroupDetailSearch.filterMembers(members, "").map { it.userId })
    }

    @Test
    fun `member query with no hit returns empty`() {
        assertTrue(GroupDetailSearch.filterMembers(listOf(member("u1", "Alice")), "zzz").isEmpty())
    }

    @Test
    fun `blank audit query returns the list unchanged`() {
        val logs = listOf(audit("a1", "MEMBER_ADDED"), audit("a2", "GROUP_RENAMED"))
        assertEquals(logs, GroupDetailSearch.filterAuditLogs(logs, ""))
    }

    @Test
    fun `audit query matches actor name id action and target`() {
        val logs = listOf(
            audit("a1", "MEMBER_ADDED", actorName = "张三", actorId = "u100"),
            audit("a2", "GROUP_RENAMED", targetUserName = "李四", targetUserId = "u200"),
        )
        assertEquals(listOf("a1"), GroupDetailSearch.filterAuditLogs(logs, "张三").map { it.id })
        assertEquals(listOf("a1"), GroupDetailSearch.filterAuditLogs(logs, "u100").map { it.id })
        assertEquals(listOf("a2"), GroupDetailSearch.filterAuditLogs(logs, "GROUP_RENAMED").map { it.id })
        assertEquals(listOf("a2"), GroupDetailSearch.filterAuditLogs(logs, "李四").map { it.id })
        assertEquals(listOf("a2"), GroupDetailSearch.filterAuditLogs(logs, "u200").map { it.id })
    }

    @Test
    fun `audit query matches through action tokens`() {
        val logs = listOf(audit("a1", "MEMBER_ADDED"), audit("a2", "MEMBER_LEFT"))
        assertEquals(listOf("a1"), GroupDetailSearch.filterAuditLogs(logs, "添加").map { it.id })
        assertEquals(listOf("a1"), GroupDetailSearch.filterAuditLogs(logs, "added").map { it.id })
        assertEquals(listOf("a2"), GroupDetailSearch.filterAuditLogs(logs, "退群").map { it.id })
    }

    @Test
    fun `audit query with no hit returns empty`() {
        assertTrue(GroupDetailSearch.filterAuditLogs(listOf(audit("a1", "MEMBER_ADDED")), "zzz").isEmpty())
    }

    @Test
    fun `candidate query matches displayName name and id case-insensitively`() {
        val candidates = listOf(
            User(id = "u1", name = "Alice", nickname = "阿狸"),
            User(id = "u2", name = "Bob"),
        )
        assertEquals(listOf("u1"), GroupDetailSearch.filterCandidates(candidates, "阿狸").map { it.id })
        assertEquals(listOf("u1"), GroupDetailSearch.filterCandidates(candidates, "ALICE").map { it.id })
        assertEquals(listOf("u2"), GroupDetailSearch.filterCandidates(candidates, "u2").map { it.id })
        assertEquals(candidates, GroupDetailSearch.filterCandidates(candidates, "  "))
    }

    @Test
    fun `candidate query is trimmed`() {
        val candidates = listOf(User(id = "u1", name = "Alice"))
        assertEquals(listOf("u1"), GroupDetailSearch.filterCandidates(candidates, "  alice ").map { it.id })
    }

    @Test
    fun `visiblePage takes page size only when collapsed and overfull`() {
        val list = (1..5).toList()
        assertEquals(list, GroupDetailSearch.visiblePage(list, expanded = true, pageSize = 2))
        assertEquals(list, GroupDetailSearch.visiblePage(list, expanded = false, pageSize = 5))
        assertEquals(listOf(1, 2), GroupDetailSearch.visiblePage(list, expanded = false, pageSize = 2))
    }
}
