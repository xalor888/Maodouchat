package com.maodouchat.server

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.migration.DatabaseMigrations
import com.maodouchat.server.db.migration.MigrationRunner
import com.maodouchat.server.db.migration.appliedMigrationVersion
import com.maodouchat.server.db.migration.expectedMigrationVersion
import com.maodouchat.server.db.migration.runDatabaseMigrations
import com.maodouchat.server.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q06 / M4：把「备份 → 恢复 → 升级」这条**联合路径**变成证据。
 *
 * 另外两个产物各证明了一半：
 * - `PostgresMigrationMatrixTest`：迁移链本身在真 PG 上正确；
 * - `scripts/rehearse-pg-restore.sh`：dump/restore 往返内容一致、坏备份会被拒。
 *
 * 但两者都没有回答真正决定「能不能升级线上」的那个问题：
 * **把一份旧版本的备份恢复出来之后，新版服务能不能把它迁到最新？**
 * 本文件就做这一件事，并且用与生产 `backup-production.sh` **完全相同**的
 * `pg_dump --create --format=custom` 参数。
 */
@Tag("postgres")
class PostgresRestoreUpgradeTest {

    private data class PgTarget(val host: String, val port: String, val db: String, val user: String, val password: String)

    private fun baseTarget(): PgTarget {
        val url = System.getenv("POSTGRES_TEST_DATABASE_URL")
            ?.takeIf(String::isNotBlank)
            ?: error("POSTGRES_TEST_DATABASE_URL is required for postgresIntegrationTest")
        val m = Regex("^jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)(?:\\?(.*))?$").find(url)
            ?: error("unexpected POSTGRES_TEST_DATABASE_URL shape: $url")
        val params = (m.groupValues.getOrNull(4) ?: "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return PgTarget(
            host = m.groupValues[1],
            port = m.groupValues[2],
            db = m.groupValues[3],
            user = params["user"] ?: error("POSTGRES_TEST_DATABASE_URL must carry user="),
            password = params["password"] ?: "",
        )
    }

    private fun jdbcFor(t: PgTarget, db: String): String =
        "jdbc:postgresql://${t.host}:${t.port}/$db?user=${t.user}" +
            if (t.password.isEmpty()) "" else "&password=${t.password}"

    /** 客户端工具可能不在 PATH 上（本机 Homebrew 就是如此），因此按 PATH → Homebrew 兜底解析。 */
    private fun resolveTool(name: String): String {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
        val homebrew = File("/opt/homebrew/opt/postgresql@16/bin/$name")
        val found = onPath ?: homebrew.takeIf { it.canExecute() }
        assertTrue(
            found != null,
            "找不到 $name：这个集成测试需要 PostgreSQL 客户端工具（CI 的 runner 自带；本机可加 PATH）",
        )
        return found!!.absolutePath
    }

    private fun runTool(command: List<String>, t: PgTarget, stdoutTo: File? = null) {
        val proc = ProcessBuilder(command).apply {
            environment()["PGPASSWORD"] = t.password
            if (stdoutTo != null) redirectOutput(stdoutTo) else redirectOutput(ProcessBuilder.Redirect.DISCARD)
            redirectErrorStream(true)
        }.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertTrue(code == 0, "命令失败（exit=$code）: ${command.joinToString(" ")}\n$output")
    }

    @Test
    fun `a production-style dump of an older database restores and then upgrades to the latest version`() {
        val base = baseTarget()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val srcDb = "maodou_ru_src_$suffix"
        val scratchDb = "maodou_ru_scratch_$suffix"
        val workDir = Files.createTempDirectory("maodou-restore-upgrade").toFile()
        val dump = File(workDir, "database.dump")

        fun admin(sql: String) =
            DriverManager.getConnection(jdbcFor(base, base.db)).use { it.createStatement().execute(sql) }

        val opened = mutableListOf<Database>()
        admin("CREATE DATABASE \"$srcDb\"")
        admin("CREATE DATABASE \"$scratchDb\"")
        try {
            // ① 造一个「旧版本」库：用真实迁移链只跑到 v3，再放一点业务数据。
            val src = Database.connect(jdbcFor(base, srcDb), driver = "org.postgresql.Driver")
            opened += src
            val olderVersions = DatabaseMigrations.all.takeWhile { it.version <= 3 }
            assertEquals(listOf(1, 2, 3), MigrationRunner(olderVersions).run(), "旧库应当停在 v3")
            UserRepository().createDefaultUsers()
            ChatsInsert.probe()
            val usersBefore = transaction { Users.selectAll().count() }
            assertTrue(usersBefore >= 13, "旧库应当有种子用户，实际 $usersBefore")

            // ② 备份：与生产 backup-production.sh 完全相同的参数。
            runTool(
                listOf(
                    resolveTool("pg_dump"), "-h", base.host, "-p", base.port, "-U", base.user,
                    "-d", srcDb, "--create", "--format=custom",
                ),
                base,
                stdoutTo = dump,
            )
            assertTrue(dump.length() > 0, "dump 不应为空")

            // ③ 恢复到一个 scratch 库。
            runTool(
                listOf(
                    resolveTool("pg_restore"), "-h", base.host, "-p", base.port, "-U", base.user,
                    "-d", scratchDb, "--no-owner", "--no-privileges", dump.absolutePath,
                ),
                base,
            )

            // ④ 关键一步：在**恢复出来的副本**上跑完整迁移链，它必须能从 v3 升到最新。
            val scratch = Database.connect(jdbcFor(base, scratchDb), driver = "org.postgresql.Driver")
            opened += scratch
            assertEquals(
                DatabaseMigrations.all.map { it.version }.filter { it > 3 },
                runDatabaseMigrations(),
                "恢复出的副本应当只补 v3 之后的版本",
            )
            assertEquals(expectedMigrationVersion(), appliedMigrationVersion(), "恢复副本必须到达最新版本")

            // ⑤ 数据必须还在（升级不能吃掉旧数据）。
            val usersAfter = transaction { Users.selectAll().count() }
            assertEquals(usersBefore, usersAfter, "升级前后用户行数必须一致")
            assertEquals(1, transaction { Chats.selectAll().where { Chats.id eq "ru_probe_chat" }.count() })
        } finally {
            opened.forEach { runCatching { TransactionManager.closeAndUnregister(it) } }
            runCatching { admin("DROP DATABASE IF EXISTS \"$srcDb\"") }
            runCatching { admin("DROP DATABASE IF EXISTS \"$scratchDb\"") }
            workDir.deleteRecursively()
        }
    }

    /** 旧库里放一条业务数据，用来验证「升级不吃数据」。 */
    private object ChatsInsert {
        fun probe() = transaction {
            Chats.insert {
                it[id] = "ru_probe_chat"
            }
        }
    }
}
