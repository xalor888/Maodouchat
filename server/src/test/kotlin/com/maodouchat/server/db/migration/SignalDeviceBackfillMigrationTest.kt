package com.maodouchat.server.db.migration

import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.backfillMissingSignalDevices
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalDeviceBackfillMigrationTest {

    private var database: Database? = null

    @AfterEach
    fun tearDown() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `migration v5 inserts missing signal_devices for keys-only slots`() {
        val dbUrl =
            "jdbc:h2:mem:signal-device-backfill-${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[Users.id] = "u1"
                it[Users.name] = "u"
                it[Users.email] = "u1@example.test"
                it[Users.passwordHash] = "x"
            }
            SignalKeys.insert {
                it[SignalKeys.id] = "sk_1"
                it[SignalKeys.userId] = "u1"
                it[SignalKeys.deviceId] = 9
                it[SignalKeys.keyType] = "identity_key"
                it[SignalKeys.keyData] = "abc"
                it[SignalKeys.createdAt] = 1L
            }
            assertTrue(SignalDevices.selectAll().empty())
            backfillMissingSignalDevices()
            val row = SignalDevices.selectAll().single()
            assertEquals("u1", row[SignalDevices.userId])
            assertEquals(9, row[SignalDevices.deviceId])
            assertEquals("CONFIRMED", row[SignalDevices.status])
            assertTrue(row[SignalDevices.confirmedAt] != null)
            // idempotent
            backfillMissingSignalDevices()
            assertEquals(1, SignalDevices.selectAll().count())
        }
    }

    @Test
    fun `full migration chain reaches version 5`() {
        val dbUrl =
            "jdbc:h2:mem:signal-device-chain-${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        assertEquals(listOf(1, 2, 3, 4, 5), runDatabaseMigrations())
        assertEquals(5, expectedMigrationVersion())
        assertEquals(5, appliedMigrationVersion())
    }
}
