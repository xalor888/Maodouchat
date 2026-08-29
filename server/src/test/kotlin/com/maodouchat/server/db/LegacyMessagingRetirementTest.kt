package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LegacyMessagingRetirementTest {
    @Test
    fun `legacy bot messages migrate before v1 tables are dropped`() {
        Database.connect(
            "jdbc:h2:mem:legacy_messaging_${counter.incrementAndGet()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        initDatabase()

        transaction {
            Users.insert {
                it[id] = "bot_legacy"
                it[name] = "Legacy Bot"
                it[email] = "legacy-bot@example.com"
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
                """.trimIndent()
            )
            TransactionManager.current().exec(
                """
                INSERT INTO messages
                    (id, chat_id, sender_id, content, "type", "timestamp", edited_at)
                VALUES
                    ('legacy_service_1', 'legacy_chat', 'bot_legacy', 'hello', 'TEXT', 42, NULL)
                """.trimIndent()
            )
            listOf("read_receipts", "message_reactions", "message_mutations", "sender_key_distributions").forEach { tableName ->
                TransactionManager.current().exec("CREATE TABLE $tableName (id INT)")
            }

            retireLegacyMessagingTables()

            val migrated = ServiceMessages.selectAll()
                .where { ServiceMessages.id eq "legacy_service_1" }
                .single()
            assertEquals("hello", migrated[ServiceMessages.content])
            listOf("messages", "read_receipts", "message_reactions", "message_mutations", "sender_key_distributions").forEach {
                assertFalse(tableExists(it), "$it should be retired")
            }
        }
    }

    private fun tableExists(tableName: String): Boolean =
        TransactionManager.current().exec(
            "SELECT 1 FROM information_schema.tables WHERE LOWER(table_name) = '$tableName'"
        ) { result -> result.next() } == true

    companion object {
        private val counter = AtomicInteger()
    }
}
