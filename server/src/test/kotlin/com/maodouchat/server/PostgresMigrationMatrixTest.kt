package com.maodouchat.server

import com.maodouchat.server.db.migration.DatabaseMigrations
import com.maodouchat.server.db.migration.MigrationRunner
import com.maodouchat.server.db.migration.appliedMigrationVersion
import com.maodouchat.server.db.migration.expectedMigrationVersion
import com.maodouchat.server.db.migration.runDatabaseMigrations
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 迁移矩阵以 **真实 PostgreSQL** 为真源（H2 只做快测）。
 *
 * `MigrationRunnerTest` 已经覆盖了 H2 上的矩阵（成功/失败回滚/重复版本拒绝/顺序校验）。
 * 但生产跑的是 PostgreSQL，而 PG 路径上有 H2 完全没有的东西：
 * `pg_advisory_xact_lock` 并发串行化、以及真实 DDL 在 PG 上的行为差异。
 * 这些在本文件之前**从未被执行过**——CI 的 `postgresIntegrationTest` 里只有一个并发测试。
 *
 * 四个场景：空库到最新、旧版本库升级、失败整体回滚且重跑收敛、双实例并发串行化。
 *
 * 每个用例用独立 schema 隔离，互不污染（沿用 `PostgresGroupConcurrencyTest` 的做法）。
 */
@Tag("postgres")
class PostgresMigrationMatrixTest {

    private fun newScopedDatabase(): String {
        val baseUrl = System.getenv("POSTGRES_TEST_DATABASE_URL")
            ?.takeIf(String::isNotBlank)
            ?: error("POSTGRES_TEST_DATABASE_URL is required for postgresIntegrationTest")
        require(baseUrl.startsWith("jdbc:postgresql://")) { "PostgreSQL integration URL required" }

        val schema = "maodou_mm_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        require(schema.matches(Regex("^maodou_mm_[a-f0-9]{12}$")))

        DriverManager.getConnection(baseUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") }
        }
        val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"
        Database.connect(scopedUrl, driver = "org.postgresql.Driver")
        return schema
    }

    private fun countRows(table: String): Int = transaction {
        TransactionManager.current().exec("SELECT COUNT(*) FROM $table") { rs ->
            rs.next()
            rs.getInt(1)
        } ?: 0
    }

    @Test
    fun `empty database migrates to the latest version and rerun is a no-op`() {
        newScopedDatabase()

        assertEquals(
            DatabaseMigrations.all.map { it.version },
            runDatabaseMigrations(),
            "空库应当一次跑完全部迁移",
        )
        assertEquals(expectedMigrationVersion(), appliedMigrationVersion())
        assertEquals(emptyList(), runDatabaseMigrations(), "第二次执行必须是 no-op")
        assertEquals(expectedMigrationVersion(), countRows("schema_migrations"), "版本表行数必须等于最新版本号")
    }

    @Test
    fun `an older database only applies the missing versions`() {
        newScopedDatabase()

        // 造一个「旧版本」库：只跑前 3 个迁移，模拟历史部署。
        val upToV3 = DatabaseMigrations.all.takeWhile { it.version <= 3 }
        assertEquals(listOf(1, 2, 3), MigrationRunner(upToV3).run())
        assertEquals(3, appliedMigrationVersion())

        // 现在按真实启动路径升级：只应补 4、5。
        assertEquals(listOf(4, 5), runDatabaseMigrations())
        assertEquals(expectedMigrationVersion(), appliedMigrationVersion())
    }

    @Test
    fun `a failing migration rolls back the whole run and a rerun converges`() {
        newScopedDatabase()

        // 跑完全部真实迁移，再追加一个「一定会炸」的迁移：
        // MigrationRunner 把整个 run 放在一个事务里，所以它必须整体回滚，
        // 不能留下「v6 改了一半、版本表却已经记了」这种中间态。
        val failing = DatabaseMigrations.all + object : com.maodouchat.server.db.migration.DatabaseMigration {
            override val version = 99
            override val description = "deliberate failure"
            override fun apply() {
                TransactionManager.current().exec("CREATE TABLE should_not_survive (id INT)")
                error("deliberate failure")
            }
        }

        val thrown = runCatching { MigrationRunner(failing).run() }.exceptionOrNull()
        assertTrue(thrown != null, "故意失败的迁移必须抛错")

        // 整体回滚：版本表里不应有 1（哪怕它排在失败项之前），也不应有 99。
        assertEquals(null, appliedMigrationVersion(), "失败的 run 不允许留下任何已应用版本")
        val survived = transaction {
            TransactionManager.current().exec(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'should_not_survive'",
            ) { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        assertEquals(0, survived, "失败迁移建的表不允许存活")

        // 重跑真实迁移应当干净地跑到最新。
        assertEquals(expectedMigrationVersion(), runDatabaseMigrations().last())
        assertEquals(expectedMigrationVersion(), appliedMigrationVersion())
    }

    /**
     * 这一条直接盯住一个曾真实存在的缺陷：
     * `ensureSearchIndexes()` 声称「pg_trgm 不可用时降级为 LIKE 全扫描」，但 PostgreSQL 里
     * 一条语句失败会让整个事务进入 aborted 状态，只 catch 并吞掉异常**并不能降级**——
     * 基线迁移 v1 会整个失败，服务起不来。
     *
     * 测试用独立 schema 让 pg_trgm 不在 search_path 上（扩展装在同库的 public），
     * 从而稳定复现「可选 DDL 失败」，然后断言迁移仍然跑到最新。
     */
    @Test
    fun `migration survives an unavailable pg_trgm and still reaches the latest version`() {
        newScopedDatabase()

        assertEquals(
            DatabaseMigrations.all.map { it.version },
            runDatabaseMigrations(),
            "pg_trgm 的索引建不出来时，迁移必须降级继续，而不是把事务污染成 aborted",
        )
        assertEquals(expectedMigrationVersion(), appliedMigrationVersion())

        // 降级发生了：这个 schema 里确实没有建成 trigram 索引。
        val trgmIndexes = transaction {
            TransactionManager.current().exec(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = CURRENT_SCHEMA " +
                    "AND indexname LIKE '%_trgm'",
            ) { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        assertEquals(0, trgmIndexes, "本 schema 不应存在 trigram 索引（pg_trgm 不可见）")
    }

    @Test
    fun `two instances racing on the same database apply each migration exactly once`() {
        newScopedDatabase()

        val results = AtomicReference<List<List<Int>>>()
        val start = CountDownLatch(1)
        val outcomes = arrayOfNulls<Result<List<Int>>>(2)
        val threads = (0 until 2).map { index ->
            Thread {
                start.await()
                outcomes[index] = runCatching { runDatabaseMigrations() }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
        threads.forEach { assertTrue(!it.isAlive, "迁移线程超时未结束——advisory lock 可能死锁") }

        val applied = outcomes.map { it!!.getOrThrow() }
        results.set(applied)

        // 两个实例合起来，每个版本恰好被应用一次：一个拿到全部，另一个拿到空。
        assertEquals(
            DatabaseMigrations.all.map { it.version },
            applied.flatten().sorted(),
            "两个实例合计必须恰好各应用一次：$applied",
        )
        assertEquals(expectedMigrationVersion(), appliedMigrationVersion(), "并发跑完只允许记录一次最新版本")
    }
}
