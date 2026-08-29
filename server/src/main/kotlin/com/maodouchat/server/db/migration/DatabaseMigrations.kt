package com.maodouchat.server.db.migration

import com.maodouchat.server.db.applyBaselineSchemaMigration
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
    )
}
