// Fuzz / random-oracle tests for GroupDetailSearch
// (checklist line 931, 132nd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: role rank is
// OWNER 0 / ADMIN 1 / else 2; member/audit/candidate filters trim the
// query and match fields with case-insensitive contains; members sort
// by role rank, online first, then lowercase display name; visiblePage
// takes the first pageSize entries unless expanded. No GroupDetailSearch
// fuzz test existed before this block (content-grep dedup 0 hits on all
// 138 remote branches, main's 127 FuzzTest files, and all 135 open PR
// titles/bodies; the bare name filterCandidates also appears in
// MentionPolicyFuzzTest, a different function).
package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun gdsRoleRankOracle(role: String): Int = when (role) {
    "OWNER" -> 0
    "ADMIN" -> 1
    else -> 2
}

private fun gdsTokensOracle(action: String): List<String> = when (action) {
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

private fun gdsFilterMembersOracle(members: List<GroupMemberUi>, query: String): List<GroupMemberUi> {
    val q = query.trim()
    val base = if (q.isBlank()) members else members.filter {
        it.displayName.contains(q, ignoreCase = true) ||
            it.name.contains(q, ignoreCase = true) ||
            it.title.orEmpty().contains(q, ignoreCase = true) ||
            it.userId.contains(q, ignoreCase = true)
    }
    return base.sortedWith(
        compareBy<GroupMemberUi> { gdsRoleRankOracle(it.role) }
            .thenByDescending { it.isOnline }
            .thenBy { it.displayName.lowercase() }
    )
}

private fun gdsFilterAuditLogsOracle(auditLogs: List<GroupAuditLogDto>, query: String): List<GroupAuditLogDto> {
    val q = query.trim()
    if (q.isBlank()) return auditLogs
    return auditLogs.filter { audit ->
        audit.actorName.contains(q, ignoreCase = true) ||
            audit.actorId.contains(q, ignoreCase = true) ||
            audit.action.contains(q, ignoreCase = true) ||
            audit.targetUserName.orEmpty().contains(q, ignoreCase = true) ||
            audit.targetUserId.orEmpty().contains(q, ignoreCase = true) ||
            gdsTokensOracle(audit.action).any { token ->
                token.contains(q, ignoreCase = true)
            }
    }
}

private fun gdsFilterCandidatesOracle(candidates: List<User>, query: String): List<User> {
    val q = query.trim()
    if (q.isBlank()) return candidates
    return candidates.filter {
        it.displayName.contains(q, ignoreCase = true) ||
            it.name.contains(q, ignoreCase = true) ||
            it.id.contains(q, ignoreCase = true)
    }
}

private fun <T> gdsVisiblePageOracle(filtered: List<T>, expanded: Boolean, pageSize: Int): List<T> =
    if (expanded || filtered.size <= pageSize) filtered else filtered.take(pageSize)

// ---------- generators (mirrored by Oracle132 GEN, seed 132001) ----------

private val GDS_ROLE_POOL = arrayOf(
    "", " ", "OWNER", "owner", "Owner", " OWNER ", "ADMIN", "admin", "Admin",
    "MEMBER", "member", "MODERATOR", "OWNERX", "XOWNER", "OWN", "ADMIN\u00A0",
    "oWnEr", "OWNER\u2003", "owner\u200B", "ADMIN ", " admin", "\u0130"
)

private val GDS_NAME_POOL = arrayOf(
    "", " ", "Alice", "alice", "ALICE", "Bob", "bob", "  Alice  ",
    "Al\u00A0ice", "A\uD800lice", "\uD83D\uDE00", "a", "A", "Zoe", "zoe",
    "ZOE", "\u03A3\u03A3", "A\u03A3", "\u03A3", "sigma", "\u03C3", "\u03C2",
    "\uFB00", "\uFB00oo", "\u0130", "\u0131", "K", "\u212A", "\uFB00oo"
)

private val GDS_QUERY_POOL = arrayOf(
    "", " ", "ali", "ALI", "Alice", "alice", "a", "A", "bob", "  ali  ",
    "owner", "MEMBER", "mem", "added", "添加", "admin", "ADMIN", "muted",
    "禁言", "invite", "邀请", "\u00A0", "zzz", "e", "E", "\u03A3",
    "\u03C3", "\u03C2", "sigma", "\uFB00", "\uFB01", "i", "I", "\u0130", "\u0131",
    "u1", "title", "Zed", "zed"
)

private val GDS_ACTION_POOL = arrayOf(
    "MEMBER_ADDED", "MEMBER_JOINED", "MEMBER_REMOVED", "MEMBER_PROMOTED",
    "MEMBER_DEMOTED", "MEMBER_MUTED", "MEMBER_UNMUTED", "GROUP_RENAMED",
    "ANNOUNCEMENT_UPDATED", "AVATAR_UPDATED", "INVITE_ROTATED",
    "INVITE_CONFIGURED", "TITLE_UPDATED", "NICKNAME_UPDATED", "MEMBER_LEFT",
    "OWNERSHIP_TRANSFERRED", "UNKNOWN", "", "member_added",
    "MEMBER_ADDED ", " member_added", "MEMBER_ADDEDX"
)

private val GDS_ID_POOL = arrayOf(
    "", " ", "u1", "u2", "a", "b", "a\uD800b", "\uDC00", "\uE000",
    "\uD83D\uDE00", "u10", "  ", "\u00A0"
)

private val GDS_LONG_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 1000L, Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private fun genGdsRole(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GDS_ROLE_POOL[rnd.nextInt(GDS_ROLE_POOL.size)]
    else randomText(rnd, 8)

private fun genGdsName(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GDS_NAME_POOL[rnd.nextInt(GDS_NAME_POOL.size)]
    else randomText(rnd, 8)

private fun genGdsId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GDS_ID_POOL[rnd.nextInt(GDS_ID_POOL.size)]
    else randomText(rnd, 6)

private fun genGdsQuery(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GDS_QUERY_POOL[rnd.nextInt(GDS_QUERY_POOL.size)]
    else randomText(rnd, 8)

private fun genGdsNameNullable(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else genGdsName(rnd)

private fun genGdsLong(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) GDS_LONG_POOL[rnd.nextInt(GDS_LONG_POOL.size)]
    else rnd.nextLong(-1_000_000_000L, 1_000_000_001L)

private fun genGdsMember(rnd: Random): GroupMemberUi = GroupMemberUi(
    userId = genGdsId(rnd),
    name = genGdsName(rnd),
    avatar = genGdsNameNullable(rnd),
    role = genGdsRole(rnd),
    title = genGdsNameNullable(rnd),
    groupNickname = genGdsNameNullable(rnd),
    joinedAt = genGdsLong(rnd),
    isOnline = rnd.nextBoolean(),
    mutedUntil = genGdsLong(rnd)
)

private fun genGdsMemberList(rnd: Random): List<GroupMemberUi> {
    val n = rnd.nextInt(0, 5)
    return List(n) { genGdsMember(rnd) }
}

private fun genGdsAction(rnd: Random): String =
    if (rnd.nextInt(2) == 0) GDS_ACTION_POOL[rnd.nextInt(GDS_ACTION_POOL.size)]
    else randomText(rnd, 10)

private fun genGdsAudit(rnd: Random): GroupAuditLogDto = GroupAuditLogDto(
    id = genGdsId(rnd),
    actorId = genGdsId(rnd),
    actorName = genGdsName(rnd),
    action = genGdsAction(rnd),
    targetUserId = genGdsNameNullable(rnd),
    targetUserName = genGdsNameNullable(rnd),
    createdAt = genGdsLong(rnd)
)

private fun genGdsAuditList(rnd: Random): List<GroupAuditLogDto> {
    val n = rnd.nextInt(0, 5)
    return List(n) { genGdsAudit(rnd) }
}

private fun genGdsUser(rnd: Random): User = User(
    id = genGdsId(rnd),
    name = genGdsName(rnd),
    avatar = genGdsNameNullable(rnd),
    email = genGdsName(rnd),
    isOnline = rnd.nextBoolean(),
    status = genGdsName(rnd),
    nickname = genGdsNameNullable(rnd),
    lastSeen = genGdsLong(rnd)
)

private fun genGdsUserList(rnd: Random): List<User> {
    val n = rnd.nextInt(0, 5)
    return List(n) { genGdsUser(rnd) }
}

private fun genGdsStringList(rnd: Random): List<String> {
    val n = rnd.nextInt(0, 6)
    return List(n) { genGdsName(rnd) }
}

private fun genGdsPageSize(rnd: Random): Int {
    val pool = intArrayOf(-2, -1, 0, 1, 2, 3, 5, 100, Int.MIN_VALUE, Int.MAX_VALUE)
    return if (rnd.nextInt(2) == 0) pool[rnd.nextInt(pool.size)] else rnd.nextInt()
}

class GroupDetailSearchFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Role ranks: exact OWNER/ADMIN only; case and space do not count.
        assertEquals(0, GroupDetailSearch.roleRank("OWNER"))
        assertEquals(1, GroupDetailSearch.roleRank("ADMIN"))
        assertEquals(2, GroupDetailSearch.roleRank("MEMBER"))
        assertEquals(2, GroupDetailSearch.roleRank("owner"))
        assertEquals(2, GroupDetailSearch.roleRank(" OWNER"))
        assertEquals(2, GroupDetailSearch.roleRank(""))
        // Action tokens: every known action maps to its table, unknown falls back.
        assertEquals(listOf("添加", "成员", "added", "member", "add"), GroupDetailSearch.groupAuditActionSearchTokens("MEMBER_ADDED"))
        assertEquals(listOf("邀请", "链接", "invite", "link"), GroupDetailSearch.groupAuditActionSearchTokens("INVITE_ROTATED"))
        assertEquals(listOf("邀请", "链接", "invite", "link"), GroupDetailSearch.groupAuditActionSearchTokens("INVITE_CONFIGURED"))
        assertEquals(listOf("转让", "群主", "owner", "transfer"), GroupDetailSearch.groupAuditActionSearchTokens("OWNERSHIP_TRANSFERRED"))
        assertEquals(listOf("操作", "activity", "group"), GroupDetailSearch.groupAuditActionSearchTokens("UNKNOWN"))
        assertEquals(listOf("操作", "activity", "group"), GroupDetailSearch.groupAuditActionSearchTokens(""))
        assertEquals(listOf("操作", "activity", "group"), GroupDetailSearch.groupAuditActionSearchTokens("member_added"))
        // Member ordering: role rank, then online first, then lowercase display name.
        val mOwner = GroupMemberUi("u1", "alice", role = "OWNER")
        val mAdminOff = GroupMemberUi("u2", "bob", role = "ADMIN")
        val mAdminOn = GroupMemberUi("u3", "carl", role = "ADMIN", isOnline = true)
        val mMemberOn = GroupMemberUi("u4", "Zoe", role = "MEMBER", isOnline = true)
        val mMemberOff = GroupMemberUi("u5", "Amy", role = "MEMBER")
        val mMemberOffB = GroupMemberUi("u6", "Bob", role = "MEMBER")
        val shuffled = listOf(mMemberOffB, mMemberOn, mAdminOff, mMemberOff, mOwner, mAdminOn)
        assertEquals(
            listOf(mOwner, mAdminOn, mAdminOff, mMemberOn, mMemberOff, mMemberOffB),
            GroupDetailSearch.filterMembers(shuffled, "")
        )
        // Blank display-name nickname falls back to the name; a real nickname wins.
        val nicked = GroupMemberUi("u7", "alice", groupNickname = "Zed")
        val blankNick = GroupMemberUi("u8", "alice", groupNickname = "  ")
        assertEquals("Zed", nicked.displayName)
        assertEquals("alice", blankNick.displayName)
        assertEquals(listOf(nicked), GroupDetailSearch.filterMembers(listOf(nicked, blankNick), "zed"))
        // Both match "alice"; the sort puts lowercase display name "alice" before "zed".
        assertEquals(listOf(blankNick, nicked), GroupDetailSearch.filterMembers(listOf(nicked, blankNick), "alice"))
        // Query matches name, title, or user id, case-insensitively; blank query returns input order, sorted.
        val titled = GroupMemberUi("u9", "x", title = "TitleKeeper")
        assertEquals(listOf(titled), GroupDetailSearch.filterMembers(listOf(titled, mOwner), "titlekeeper"))
        assertEquals(listOf(mOwner), GroupDetailSearch.filterMembers(listOf(titled, mOwner), "u1"))
        assertEquals(emptyList(), GroupDetailSearch.filterMembers(listOf(titled, mOwner), "zzz"))
        // Audit filter matches actor/action/target fields, and action search tokens.
        val log = GroupAuditLogDto("l1", "a1", "Alice", "MEMBER_MUTED", "t1", "Bob", 0L)
        assertEquals(listOf(log), GroupDetailSearch.filterAuditLogs(listOf(log), "alice"))
        assertEquals(listOf(log), GroupDetailSearch.filterAuditLogs(listOf(log), "MEMBER_MUTED"))
        assertEquals(listOf(log), GroupDetailSearch.filterAuditLogs(listOf(log), "禁言"))
        assertEquals(listOf(log), GroupDetailSearch.filterAuditLogs(listOf(log), "bob"))
        assertEquals(listOf(log), GroupDetailSearch.filterAuditLogs(listOf(log), "t1"))
        assertEquals(emptyList(), GroupDetailSearch.filterAuditLogs(listOf(log), "zzz"))
        assertEquals(listOf(log), GroupDetailSearch.filterAuditLogs(listOf(log), ""))
        // Candidate filter matches display name (nickname wins), name, or id.
        val cand = User("u1", "alice", nickname = "Ally")
        assertEquals(listOf(cand), GroupDetailSearch.filterCandidates(listOf(cand), "ally"))
        assertEquals(listOf(cand), GroupDetailSearch.filterCandidates(listOf(cand), "alice"))
        assertEquals(listOf(cand), GroupDetailSearch.filterCandidates(listOf(cand), "u1"))
        assertEquals(emptyList(), GroupDetailSearch.filterCandidates(listOf(cand), "zzz"))
        // Visible page: expanded or short lists pass through; otherwise take pageSize.
        assertEquals(listOf("a", "b", "c"), GroupDetailSearch.visiblePage(listOf("a", "b", "c"), false, 3))
        assertEquals(listOf("a", "b"), GroupDetailSearch.visiblePage(listOf("a", "b", "c"), false, 2))
        assertEquals(listOf("a", "b", "c"), GroupDetailSearch.visiblePage(listOf("a", "b", "c"), true, 2))
        assertEquals(emptyList(), GroupDetailSearch.visiblePage(listOf("a", "b", "c"), false, 0))
        assertEquals(listOf("a", "b", "c"), GroupDetailSearch.visiblePage(listOf("a", "b", "c"), false, 100))
        // take() requires a non-negative count: a negative page size throws
        // unless expanded short-circuits before take().
        assertFailsWith<IllegalArgumentException> { GroupDetailSearch.visiblePage(listOf("a", "b", "c"), false, -1) }
        assertFailsWith<IllegalArgumentException> { GroupDetailSearch.visiblePage(listOf("a", "b", "c"), false, -2) }
        assertEquals(listOf("a", "b", "c"), GroupDetailSearch.visiblePage(listOf("a", "b", "c"), true, -1))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle132.genGds consumption order exactly, so seed
        // 132001 replays the same 1500 GEN lines drive132.py ran.
        val rnd = Random(132001)
        repeat(1500) {
            when (rnd.nextInt(6)) {
                0 -> {
                    val role = genGdsRole(rnd)
                    assertEquals(gdsRoleRankOracle(role), GroupDetailSearch.roleRank(role))
                }
                1 -> {
                    val action = genGdsAction(rnd)
                    assertEquals(gdsTokensOracle(action), GroupDetailSearch.groupAuditActionSearchTokens(action))
                }
                2 -> {
                    val members = genGdsMemberList(rnd)
                    val query = genGdsQuery(rnd)
                    assertEquals(gdsFilterMembersOracle(members, query), GroupDetailSearch.filterMembers(members, query))
                }
                3 -> {
                    val logs = genGdsAuditList(rnd)
                    val query = genGdsQuery(rnd)
                    assertEquals(gdsFilterAuditLogsOracle(logs, query), GroupDetailSearch.filterAuditLogs(logs, query))
                }
                4 -> {
                    val candidates = genGdsUserList(rnd)
                    val query = genGdsQuery(rnd)
                    assertEquals(gdsFilterCandidatesOracle(candidates, query), GroupDetailSearch.filterCandidates(candidates, query))
                }
                else -> {
                    val items = genGdsStringList(rnd)
                    val expanded = rnd.nextBoolean()
                    val pageSize = genGdsPageSize(rnd)
                    if (!expanded && pageSize < 0) {
                        // take() throws on a negative count; both sides throw.
                        assertFailsWith<IllegalArgumentException> { GroupDetailSearch.visiblePage(items, expanded, pageSize) }
                        assertFailsWith<IllegalArgumentException> { gdsVisiblePageOracle(items, expanded, pageSize) }
                    } else {
                        assertEquals(
                            gdsVisiblePageOracle(items, expanded, pageSize),
                            GroupDetailSearch.visiblePage(items, expanded, pageSize)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool role / action through the pure tables.
        for (role in GDS_ROLE_POOL) {
            assertEquals(gdsRoleRankOracle(role), GroupDetailSearch.roleRank(role))
        }
        for (action in GDS_ACTION_POOL) {
            assertEquals(gdsTokensOracle(action), GroupDetailSearch.groupAuditActionSearchTokens(action))
        }
        // Small member universes through every pool query.
        val members = listOf(
            GroupMemberUi("u1", "Alice", role = "OWNER", isOnline = true),
            GroupMemberUi("u2", "bob", role = "ADMIN", title = "Keeper"),
            GroupMemberUi("u3", "Zoe", role = "MEMBER", groupNickname = "zed"),
            GroupMemberUi("u4", "alice", role = "MEMBER")
        )
        for (query in GDS_QUERY_POOL) {
            assertEquals(gdsFilterMembersOracle(members, query), GroupDetailSearch.filterMembers(members, query))
            assertEquals(gdsFilterMembersOracle(emptyList(), query), GroupDetailSearch.filterMembers(emptyList(), query))
        }
        val logs = listOf(
            GroupAuditLogDto("l1", "a1", "Alice", "MEMBER_MUTED", "t1", "Bob", 0L),
            GroupAuditLogDto("l2", "a2", "bob", "GROUP_RENAMED", null, null, 1L),
            GroupAuditLogDto("l3", "a3", "Zoe", "UNKNOWN", "u9", null, 2L)
        )
        for (query in GDS_QUERY_POOL) {
            assertEquals(gdsFilterAuditLogsOracle(logs, query), GroupDetailSearch.filterAuditLogs(logs, query))
        }
        val candidates = listOf(
            User("u1", "Alice", nickname = "Ally"),
            User("u2", "bob"),
            User("u3", "Zoe", nickname = "  ")
        )
        for (query in GDS_QUERY_POOL) {
            assertEquals(gdsFilterCandidatesOracle(candidates, query), GroupDetailSearch.filterCandidates(candidates, query))
        }
        // Visible page over fixed lists and edge page sizes.
        val items = listOf("a", "b", "c", "d")
        for (pageSize in listOf(-2, -1, 0, 1, 2, 3, 4, 5, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            for (expanded in listOf(false, true)) {
                if (!expanded && pageSize < 0) {
                    assertFailsWith<IllegalArgumentException> { GroupDetailSearch.visiblePage(items, expanded, pageSize) }
                    assertFailsWith<IllegalArgumentException> { gdsVisiblePageOracle(items, expanded, pageSize) }
                } else {
                    assertEquals(gdsVisiblePageOracle(items, expanded, pageSize), GroupDetailSearch.visiblePage(items, expanded, pageSize))
                }
            }
        }
        // Whitespace/control singles as queries and roles.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(gdsRoleRankOracle(s), GroupDetailSearch.roleRank(s))
            assertEquals(gdsTokensOracle(s), GroupDetailSearch.groupAuditActionSearchTokens(s))
            assertEquals(gdsFilterMembersOracle(members, s), GroupDetailSearch.filterMembers(members, s))
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(gdsRoleRankOracle(s), GroupDetailSearch.roleRank(s))
            assertEquals(gdsTokensOracle(s), GroupDetailSearch.groupAuditActionSearchTokens(s))
        }
    }
}
