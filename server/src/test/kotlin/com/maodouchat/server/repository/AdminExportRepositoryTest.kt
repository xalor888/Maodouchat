package com.maodouchat.server.repository

import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G186c：`AdminExportRepository` —— 管理员数据导出查询层。
 *
 * 749 行 / 30 fun / 33 transaction 块，**此前零测试**。它是 M2「AdminExportsRouting
 * 不再直写 Exposed」那轮重构的产物（原 1110 行内联 SQL + CSV 映射被拆成
 * Repository + Service），但**新边界自身从无测试**。
 *
 * 它承载管理员导出：users / pushTokens / moderationAudit / riskEvents /
 * sessionsSummary … 全是敏感数据，导出行数或字段错一项就是数据事故。
 *
 * 测试基建沿用项目已有形状（参考 `AccountDeactivationCleanupTest`）：
 * H2 内存库 + `initDatabase()` + `transaction { insert }`。
 *
 * 本轮聚焦三件事（按目标）：
 *  1. 空库与有种子的基本查询正确性；
 *  2. **`limit` 语义**（0 / 负值 / 超大值）——URL 参数直接传到这里；
 *  3. **排序方向**（users 按 lastSeen DESC）。
 */
class AdminExportRepositoryTest {

    private lateinit var repo: AdminExportRepository

    @BeforeEach
    fun setUp() {
        Database.connect(
            "jdbc:h2:mem:aer_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        initDatabase()
        repo = AdminExportRepository()
    }

    @AfterEach
    fun tearDown() {
        transaction { exec("DROP ALL OBJECTS") }
    }

    /**
     * 用 Exposed DSL 而不是裸 SQL——H2 把 `name` 当保留字，
     * 裸 `INSERT INTO users (name, ...)` 会报 `Column "NAME" not found`。
     * DSL 的列引用 `Users.name` 会被正确加引号。（项目里
     * `AccountDeactivationCleanupTest.insertUser` 就是这个形状。）
     */
    private fun seedUser(id: String, lastSeen: Long, name: String = "n_$id", email: String = "$id@x.com") {
        transaction {
            Users.insert {
                it[Users.id] = id
                it[Users.name] = name
                it[Users.email] = email
                it[Users.passwordHash] = "h"
                it[Users.lastSeen] = lastSeen
            }
        }
    }

    // ---- 空库与基本查询 ----

    @Test
    fun `empty database yields empty rows for the user export`() {
        assertTrue(repo.users(10).isEmpty(), "空库必须返回空列表而不是抛异常")
    }

    @Test
    fun `users export returns one row per seeded user with the id first`() {
        seedUser("u1", 1_000L)
        seedUser("u2", 2_000L)
        val rows = repo.users(10)
        assertEquals(2, rows.size, "行数必须等于种子用户数")
        // 每行第一列是用户 id
        assertEquals(setOf("u1", "u2"), rows.map { it.first() }.toSet())
    }

    @Test
    fun `users export row has a stable column count`() {
        seedUser("u1", 1_000L)
        val rows = repo.users(10)
        assertEquals(1, rows.size)
        // users 投影片段：id, name, email, status, isOnline, isModerator, suspendedUntil, lastSeen
        assertEquals(8, rows.single().size, "列数必须稳定——CSV 表头按列数对齐，少一列就错位")
    }

    // ---- 排序方向 ----

    @Test
    fun `users export is ordered by last seen descending`() {
        seedUser("old", 1_000L)
        seedUser("mid", 2_000L)
        seedUser("new", 3_000L)
        val ids = repo.users(10).map { it.first() }
        assertEquals(listOf("new", "mid", "old"), ids, "必须按 lastSeen 倒序（最近活跃在前）")
    }

    // ---- limit 语义（URL 参数直接到这里）----

    @Test
    fun `a limit larger than the row count returns everything`() {
        repeat(3) { seedUser("u$it", 1_000L * it) }
        assertEquals(3, repo.users(10_000).size, "limit 大于行数应返回全部")
    }

    @Test
    fun `a zero limit returns no rows`() {
        seedUser("u1", 1_000L)
        // Exposed 的 limit(0) 语义：记录实际行为。若是返回 0 行，这是正确的（管理员看不到任何行）。
        val rows = repo.users(0)
        assertTrue(rows.isEmpty(), "limit=0 应返回 0 行，实际 ${rows.size} 行")
    }

    @Test
    fun `a negative limit throws and that is why the route clamps it`() {
        // G186c 实测：Repository 层对负 limit **不设防**——Exposed 直接生成
        // `FETCH -5`，H2 抛 ExposedSQLException("Invalid value -5 for parameter result FETCH")。
        //
        // 这是否是真实风险？**不是**：路由层每一处都先
        // `(queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)`
        // （AdminExportsRouting.kt 的 61/77/95/120 行…），负值根本到不了这里。
        //
        // 这条用例的价值是**把这个契约钉住**：将来谁给 Repository 加一个不经路由的
        // 调用方，或谁把 coerceIn 去掉，这里会提醒他「下界曾经由路由层保证」。
        seedUser("u1", 1_000L)
        val e = assertFailsWith<org.jetbrains.exposed.exceptions.ExposedSQLException> {
            repo.users(-5)
        }
        assertTrue(
            e.message?.contains("Invalid value") == true,
            "应因负 FETCH 被 H2 拒，实际 ${e.message?.take(80)}",
        )
    }

    @Test
    fun `limit one returns exactly one row`() {
        repeat(3) { seedUser("u$it", 1_000L + it) }
        assertEquals(1, repo.users(1).size, "limit=1 必须只返回 1 行")
    }

    @Test
    fun `a limit truncates to the requested count`() {
        repeat(5) { seedUser("u$it", 1_000L + it) }
        assertEquals(2, repo.users(2).size, "limit=2 必须只返回 2 行")
    }

    // ---- 无参查询 ----

    @Test
    fun `message stats on an empty database is queryable`() {
        // messageStats() 无参，返回 MessageStats；空库不应抛
        val stats = repo.messageStats()
        assertTrue(stats.total >= 0L, "空库的总数应为 0 或非负，实际 ${stats.total}")
    }

    // ---- G186c 补：审计/风险类查询（目标第 (6) 条要求必须含一个）----

    private fun seedAuditRow(id: String, action: String, detail: String, createdAt: Long) {
        transaction {
            ModerationAuditLog.insert {
                it[ModerationAuditLog.id] = id
                it[ModerationAuditLog.actorId] = "actor_$id"
                it[ModerationAuditLog.userId] = "target_$id"
                it[ModerationAuditLog.action] = action
                it[ModerationAuditLog.detail] = detail
                it[ModerationAuditLog.createdAt] = createdAt
            }
        }
    }

    @Test
    fun `moderation audit export is empty on a fresh database`() {
        assertTrue(repo.moderationAudit(10).isEmpty(), "空库必须返回空列表")
    }

    @Test
    fun `moderation audit export returns one row per log entry with a stable column count`() {
        seedAuditRow("a1", "ban", "spam", 1_000L)
        seedAuditRow("a2", "unban", "appeal accepted", 2_000L)
        val rows = repo.moderationAudit(10)
        assertEquals(2, rows.size, "行数必须等于审计日志条数")
        // 列数必须稳定：CSV 表头按列数对齐，少一列就整体错位
        assertEquals(rows.first().size, rows.last().size, "同一导出的每行列数必须一致")
        assertTrue(rows.first().size >= 5, "审计导出至少应有 id/actor/user/action/detail 五列，实际 ${rows.first().size}")
    }

    @Test
    fun `moderation audit export is ordered newest first`() {
        seedAuditRow("old", "ban", "x", 1_000L)
        seedAuditRow("new", "ban", "x", 9_000L)
        val ids = repo.moderationAudit(10).map { it.first() }
        assertEquals(listOf("new", "old"), ids, "审计日志必须按 createdAt 倒序（最新在前）")
    }

    @Test
    fun `moderation audit export honours the limit`() {
        repeat(4) { seedAuditRow("a$it", "ban", "x", 1_000L + it) }
        assertEquals(2, repo.moderationAudit(2).size, "limit=2 必须只返回 2 行")
    }

    @Test
    fun `blocked users export lists blocker and blocked pairs`() {
        // BlockedUsers 只有两列（blocker_id, blocked_id），且外键指向 users
        seedUser("u_blocker", 1_000L)
        seedUser("u_blocked", 1_000L)
        transaction {
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = "u_blocker"
                it[BlockedUsers.blockedId] = "u_blocked"
            }
        }
        val rows = repo.blockedUsers(10)
        assertEquals(1, rows.size, "一条拉黑记录应导出一行")
        assertEquals(listOf("u_blocker", "u_blocked"), rows.single().map { it.toString() })
    }

    @Test
    fun `blocked users export is empty when nobody is blocked`() {
        assertTrue(repo.blockedUsers(10).isEmpty(), "没有拉黑记录时必须返回空列表")
    }

    // ---- G186d：再把覆盖从 4 个 fun 扩到 ~10 个 ----

    private fun seedUser(
        id: String,
        lastSeen: Long,
        totpEnabled: Boolean = false,
        showOnline: Boolean = true,
        showStatus: Boolean = true,
        searchable: Boolean = true,
        email: String = "$id@x.com",
    ) {
        transaction {
            Users.insert {
                it[Users.id] = id
                it[Users.name] = "n_$id"
                it[Users.email] = email
                it[Users.passwordHash] = "h"
                it[Users.lastSeen] = lastSeen
                it[Users.totpEnabled] = totpEnabled
                it[Users.showOnline] = showOnline
                it[Users.showStatus] = showStatus
                it[Users.searchable] = searchable
            }
        }
    }

    // ---- Users 系的四个导出 ----

    @Test
    fun `online presence export shows the presence flags per user`() {
        seedUser("u_online", 1_000L, showOnline = true)
        seedUser("u_hidden", 2_000L, showOnline = false)
        val rows = repo.onlinePresence(10)
        assertEquals(2, rows.size, "每个用户一行")
        assertEquals(4, rows.first().size, "列数必须稳定（id/isOnline/lastSeen/showOnline）")
        val byId = rows.associateBy { it.first().toString() }
        // 列序实测为 id/isOnline/lastSeen/showOnline；isOnline 我没 seed（默认 false）
        assertEquals("false", byId["u_online"]?.get(1), "未 seed isOnline 的用户该列为 false")
        assertEquals("1000", byId["u_online"]?.get(2), "lastSeen 列必须是种子时间戳")
        assertEquals("true", byId["u_online"]?.get(3), "showOnline=true 的用户该列必须是 true")
        assertEquals("false", byId["u_hidden"]?.get(3), "showOnline=false 的用户该列必须是 false")
    }

    @Test
    fun `privacy flags export shows the three privacy switches`() {
        seedUser("u_open", 1_000L, showStatus = true, searchable = true)
        seedUser("u_closed", 1_000L, showOnline = false, showStatus = false, searchable = false)
        val rows = repo.privacyFlags(10)
        val byId = rows.associateBy { it.first().toString() }
        assertEquals(4, rows.first().size, "列数必须稳定（id/showOnline/showStatus/searchable）")
        assertEquals("false", byId["u_closed"]?.get(1), "showOnline=false 必须如实导出")
        assertEquals("false", byId["u_closed"]?.get(2), "showStatus=false 必须如实导出")
        assertEquals("false", byId["u_closed"]?.get(3), "searchable=false 必须如实导出")
        assertEquals("true", byId["u_open"]?.get(3))
    }

    @Test
    fun `identity users export mixes identity flags with a masked email`() {
        seedUser("u1", 1_000L, searchable = true, showOnline = true, totpEnabled = false, email = "user@example.com")
        val rows = repo.identityUsers(10)
        assertEquals(1, rows.size)
        assertEquals(5, rows.single().size, "列数必须稳定")
        assertEquals("use***", rows.single()[4], "邮箱必须脱敏成 前3字符+***")
    }

    @Test
    fun `totp users export only includes users who enabled two factor`() {
        // 本目标最重要的一条：2FA 过滤漏一个就是数据越权
        seedUser("u_2fa_on", 1_000L, totpEnabled = true)
        seedUser("u_2fa_off", 2_000L, totpEnabled = false)
        val ids = repo.totpUsers(10).map { it.first().toString() }
        assertEquals(listOf("u_2fa_on"), ids, "只应导出了启用 2FA 的用户")
    }

    @Test
    fun `totp users export is empty when nobody enabled two factor`() {
        repeat(3) { seedUser("u$it", 1_000L + it, totpEnabled = false) }
        assertTrue(repo.totpUsers(10).isEmpty(), "无人启用 2FA 时必须导出空列表")
    }

    @Test
    fun `totp users export masks short emails without crashing`() {
        seedUser("u_short", 1_000L, totpEnabled = true, email = "a@b.c")
        val rows = repo.totpUsers(10)
        assertEquals(1, rows.size)
        // take(3) 对短串是安全的（Kotlin 的 take 不会越界），钉住实际形状
        assertEquals("a@b***", rows.single()[2], "短邮箱脱敏后应保持原样加 ***")
    }

    @Test
    fun `identity users export also masks short emails`() {
        seedUser("u_s", 1_000L, email = "ab@c.d")
        assertEquals("ab@***", repo.identityUsers(10).single()[4], "2 字符邮箱脱敏形状")
    }

    // ---- 非 Users 系的单表查询 ----

    @Test
    fun `poll votes export lists each vote with its option index`() {
        transaction {
            GroupPollVotes.insert {
                it[GroupPollVotes.pollId] = "p1"
                it[GroupPollVotes.userId] = "u1"
                it[GroupPollVotes.optionIndex] = 2
                it[GroupPollVotes.votedAt] = 1_000L
            }
        }
        val rows = repo.pollVotes(10)
        assertEquals(1, rows.size, "一票一行")
        assertEquals(4, rows.single().size, "列数必须稳定（pollId/userId/optionIndex/votedAt）")
        assertEquals("2", rows.single()[2], "optionIndex 必须如实导出")
    }

    @Test
    fun `poll votes export is empty without votes`() {
        assertTrue(repo.pollVotes(10).isEmpty())
    }

    @Test
    fun `group invites export only includes chats that have an invite token`() {
        // 有 token 的群
        transaction {
            Chats.insert {
                it[Chats.id] = "c_with_token"
                it[Chats.isGroup] = true
                it[Chats.groupInviteToken] = "tok_abcdef123456"
                it[Chats.groupInviteExpiresAt] = 9_000L
                it[Chats.groupInviteMaxUses] = 10
                it[Chats.groupInviteUseCount] = 3
            }
        }
        // 没有 token 的群
        transaction {
            Chats.insert {
                it[Chats.id] = "c_no_token"
                it[Chats.isGroup] = true
                it[Chats.groupInviteToken] = null
            }
        }
        val rows = repo.groupInvites(10)
        assertEquals(1, rows.size, "只应导出有邀请 token 的会话")
        assertEquals(5, rows.single().size, "列数必须稳定")
        assertEquals("tok_abcdef12", rows.single()[1], "token 应截断到 12 字符")
        assertEquals("9000", rows.single()[2], "过期时间戳必须如实导出")
    }
}
