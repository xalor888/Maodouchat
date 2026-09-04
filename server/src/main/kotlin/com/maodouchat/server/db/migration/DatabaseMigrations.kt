package com.maodouchat.server.db.migration

import com.maodouchat.server.db.addSignalingEpochSequenceColumns
import com.maodouchat.server.db.applyBaselineSchemaMigration
import com.maodouchat.server.db.backfillDirectChatPairs
import com.maodouchat.server.db.retireLegacyMessagingTables
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/** Explicit entry point for startup/deployment orchestration after Exposed connects to the database. */
fun runDatabaseMigrations(): List<Int> = MigrationRunner(DatabaseMigrations.all).run()

/**
 * B14：就绪探针——最新已应用迁移版本。
 * 表不存在（测试/直接建表环境）或为空时返回 null，由调用方按「未走迁移」判定。
 */
fun appliedMigrationVersion(): Int? = runCatching {
    transaction {
        TransactionManager.current().exec(
            "SELECT version FROM schema_migrations"
        ) { rs ->
            buildList {
                while (rs.next()) add(rs.getInt(1))
            }
        }.orEmpty().maxOrNull()
    }
}.getOrNull()

/** B14：就绪探针——期望的最新迁移版本。 */
fun expectedMigrationVersion(): Int = DatabaseMigrations.all.last().version

internal object DatabaseMigrations {
    val all: List<DatabaseMigration> = listOf(
        object : DatabaseMigration {
            override val version = 1
            override val description = "Baseline schema and historical corrective changes"

            override fun apply() {
                applyBaselineSchemaMigration()
            }
        },
        object : DatabaseMigration {
            override val version = 2
            override val description = "Retire legacy plaintext messaging tables after human-data guard"

            override fun apply() {
                retireLegacyMessagingTables()
            }
        },
        object : DatabaseMigration {
            override val version = 3
            override val description = "Backfill direct_chat_pairs mapping for existing 1:1 chats"

            override fun apply() {
                backfillDirectChatPairs()
            }
        },
        object : DatabaseMigration {
            override val version = 4
            override val description = "Add signaling epoch/seq_no/idempotency_key columns"

            override fun apply() {
                addSignalingEpochSequenceColumns()
            }
        },
    )
}
