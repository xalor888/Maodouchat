package com.maodouchat.server.db.migration

import com.maodouchat.server.db.addSignalingEpochSequenceColumns
import com.maodouchat.server.db.applyBaselineSchemaMigration
import com.maodouchat.server.db.backfillDirectChatPairs
import com.maodouchat.server.db.retireLegacyMessagingTables

/** Explicit entry point for startup/deployment orchestration after Exposed connects to the database. */
fun runDatabaseMigrations(): List<Int> = MigrationRunner(DatabaseMigrations.all).run()

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
