package com.maodouchat.server.repository

import com.maodouchat.server.db.initDatabase
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
}
