package com.maodouchat.server.db.migration

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/** A one-way, ordered database change. Versions are immutable once deployed. */
internal interface DatabaseMigration {
    val version: Int
    val description: String

    fun apply()
}

/**
 * Runs migrations under one database transaction and records a version only after its change succeeds.
 * PostgreSQL uses a transaction advisory lock; H2 locks a singleton row for the transaction.
 */
internal class MigrationRunner(
    private val migrations: List<DatabaseMigration>,
) {
    init {
        require(migrations.map { it.version }.all { it > 0 }) { "Migration versions must be positive." }
        require(migrations.map { it.version } == migrations.map { it.version }.distinct()) {
            "Migration versions must be unique."
        }
        require(migrations.zipWithNext().all { (previous, next) -> previous.version < next.version }) {
            "Migrations must be declared in strictly increasing version order."
        }
    }

    fun run(): List<Int> = transaction {
        when {
            TransactionManager.current().db.vendor.contains("postgres", ignoreCase = true) -> {
                acquireMigrationLock()
                createHistoryTable()
            }
            TransactionManager.current().db.vendor.contains("h2", ignoreCase = true) -> {
                createHistoryTable()
                ensureH2LockRow()
                acquireMigrationLock()
            }
            else -> error("Database migrations support only H2 and PostgreSQL; found '${TransactionManager.current().db.vendor}'.")
        }
        val appliedVersions = appliedVersions()
        migrations.filterNot { it.version in appliedVersions }.map { migration ->
            migration.apply()
            recordAppliedMigration(migration)
            migration.version
        }
    }

    private fun createHistoryTable() {
        TransactionManager.current().exec(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                installed_at BIGINT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun ensureH2LockRow() {
        if (!TransactionManager.current().db.vendor.contains("h2", ignoreCase = true)) return
        TransactionManager.current().exec(
            "CREATE TABLE IF NOT EXISTS schema_migration_lock (id INTEGER PRIMARY KEY)"
        )
        TransactionManager.current().exec(
            "MERGE INTO schema_migration_lock (id) KEY(id) VALUES (1)"
        )
    }

    private fun acquireMigrationLock() {
        val vendor = TransactionManager.current().db.vendor.lowercase()
        when {
            vendor.contains("postgres") -> TransactionManager.current().exec(
                "SELECT pg_advisory_xact_lock(847296531)"
            )
            vendor.contains("h2") -> TransactionManager.current().exec(
                "SELECT id FROM schema_migration_lock WHERE id = 1 FOR UPDATE"
            )
            else -> error("Database migrations support only H2 and PostgreSQL; found '$vendor'.")
        }
    }

    private fun appliedVersions(): Set<Int> = TransactionManager.current().exec(
        "SELECT version FROM schema_migrations"
    ) { result ->
        buildSet {
            while (result.next()) add(result.getInt(1))
        }
    }.orEmpty()

    private fun recordAppliedMigration(migration: DatabaseMigration) {
        val description = migration.description.replace("'", "''")
        TransactionManager.current().exec(
            "INSERT INTO schema_migrations (version, description, installed_at) VALUES " +
                "(${migration.version}, '$description', ${System.currentTimeMillis()})"
        )
    }
}
