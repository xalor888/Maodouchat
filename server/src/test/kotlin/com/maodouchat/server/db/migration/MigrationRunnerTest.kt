package com.maodouchat.server.db.migration

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.db.retireLegacyMessagingTables
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationRunnerTest {
    @Test
    fun `records a successful migration and does not rerun it`() {
        connectDatabase()
        val runner = MigrationRunner(
            listOf(
                migration(1, "create marker") {
                    TransactionManager.current().exec("CREATE TABLE migration_marker (id INT PRIMARY KEY)")
                },
            ),
        )

        assertEquals(listOf(1), runner.run())
        assertEquals(emptyList(), runner.run())
        transaction {
            assertEquals(setOf(1), appliedVersions())
        }
    }

    @Test
    fun `failed migration is rolled back and remains unapplied`() {
        connectDatabase()
        transaction {
            TransactionManager.current().exec("CREATE TABLE failed_migration_marker (id INT PRIMARY KEY)")
        }
        val runner = MigrationRunner(
            listOf(
                migration(1, "fail after writing") {
                    TransactionManager.current().exec("INSERT INTO failed_migration_marker (id) VALUES (1)")
                    error("expected failure")
                },
            ),
        )

        assertFailsWith<IllegalStateException> { runner.run() }
        transaction {
            val markerCount = TransactionManager.current().exec(
                "SELECT COUNT(*) FROM failed_migration_marker"
            ) { result ->
                result.next()
                result.getInt(1)
            }
            assertEquals(0, markerCount)
            assertEquals(emptySet(), appliedVersions())
        }
    }

    @Test
    fun `rejects duplicate and out of order versions`() {
        assertFailsWith<IllegalArgumentException> {
            MigrationRunner(listOf(migration(1, "one") {}, migration(1, "duplicate") {}))
        }
        assertFailsWith<IllegalArgumentException> {
            MigrationRunner(listOf(migration(2, "two") {}, migration(1, "one") {}))
        }
    }

    @Test
    fun `published migrations apply once after safe schema initialization`() {
        connectDatabase()
        initDatabase()

        assertEquals(listOf(1, 2), runDatabaseMigrations())
        assertEquals(emptyList(), runDatabaseMigrations())
        transaction {
            assertEquals(setOf(1, 2), appliedVersions())
        }
    }

    @Test
    fun `legacy retirement refuses to drop human authored messages`() {
        connectDatabase()
        initDatabase()
        transaction {
            Users.insert {
                it[id] = "human_legacy"
                it[name] = "Human"
                it[email] = "human-legacy@example.com"
                it[passwordHash] = "deleted"
            }
            Chats.insert { it[id] = "legacy_chat" }
            TransactionManager.current().exec(
                """
                CREATE TABLE messages (
                    id VARCHAR(100) PRIMARY KEY,
                    chat_id VARCHAR(50) NOT NULL,
                    sender_id VARCHAR(50) NOT NULL,
                    content CLOB NOT NULL,
                    "type" VARCHAR(20) NOT NULL,
                    "timestamp" BIGINT NOT NULL,
                    edited_at BIGINT NULL
                )
                """.trimIndent(),
            )
            TransactionManager.current().exec(
                """
                INSERT INTO messages (id, chat_id, sender_id, content, "type", "timestamp", edited_at)
                VALUES ('human_legacy_1', 'legacy_chat', 'human_legacy', 'preserve me', 'TEXT', 42, NULL)
                """.trimIndent(),
            )

            assertFailsWith<IllegalStateException> { retireLegacyMessagingTables() }
            assertTrue(tableExists("messages"))
        }
    }

    private fun connectDatabase() {
        Database.connect(
            "jdbc:h2:mem:migration_runner_${counter.incrementAndGet()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
    }

    private fun migration(version: Int, description: String, apply: () -> Unit): DatabaseMigration =
        object : DatabaseMigration {
            override val version = version
            override val description = description

            override fun apply() = apply()
        }

    private fun appliedVersions(): Set<Int> = TransactionManager.current().exec(
        "SELECT version FROM schema_migrations",
    ) { result ->
        buildSet {
            while (result.next()) add(result.getInt(1))
        }
    }.orEmpty()

    private fun tableExists(tableName: String): Boolean = TransactionManager.current().exec(
        "SELECT 1 FROM information_schema.tables WHERE LOWER(table_name) = '$tableName'",
    ) { result -> result.next() } == true

    companion object {
        private val counter = AtomicInteger()
    }
}
