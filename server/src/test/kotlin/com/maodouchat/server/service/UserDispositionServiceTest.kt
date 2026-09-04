package com.maodouchat.server.service

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B13：UserDispositionService 写命令门面的校验与原子写+审计。
 */
class UserDispositionServiceTest {

    private var database: Database? = null
    private val dbUrl =
        "jdbc:h2:mem:user-disposition-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[Users.id] = "u1"
                it[Users.name] = "u1"
                it[Users.email] = "u1@test.local"
                it[Users.passwordHash] = "x"
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `suspend writes field and audit atomically`() {
        setupDb()
        val until = System.currentTimeMillis() + 7L * 86_400_000L
        val result = UserDispositionService(UserRepository()).suspend("admin", "u1", until, "spam", null)
        val applied = assertIs<UserDispositionService.Result.Applied>(result)
        assertEquals("spam", applied.reasonCode)

        transaction {
            val row = Users.selectAll().where { Users.id eq "u1" }.single()
            assertEquals(until, row[Users.suspendedUntil])
            val audit = ModerationAuditLog.selectAll()
                .where { (ModerationAuditLog.userId eq "u1") and (ModerationAuditLog.action eq "ADMIN_STATUS_UPDATE") }
                .single()
            assertEquals("admin", audit[ModerationAuditLog.actorId])
            assertTrue(audit[ModerationAuditLog.detail].orEmpty().contains("reasonCode=spam"))
        }
    }

    @Test
    fun `invalid until is rejected`() {
        setupDb()
        val past = System.currentTimeMillis() - 1_000L
        val result = UserDispositionService(UserRepository()).suspend("admin", "u1", past, "spam", null)
        assertIs<UserDispositionService.Result.Invalid>(result)
    }

    @Test
    fun `missing user yields not found`() {
        setupDb()
        val until = System.currentTimeMillis() + 86_400_000L
        val result = UserDispositionService(UserRepository()).suspend("admin", "missing", until, "spam", null)
        assertEquals(UserDispositionService.Result.NotFound, result)
    }

    @Test
    fun `restrict messages writes field and audit`() {
        setupDb()
        val until = System.currentTimeMillis() + 3L * 86_400_000L
        val result = UserDispositionService(UserRepository()).restrictMessages("admin", "u1", until, "spam_chat", null)
        assertIs<UserDispositionService.Result.Applied>(result)
        transaction {
            val row = Users.selectAll().where { Users.id eq "u1" }.single()
            assertEquals(until, row[Users.messageRestrictedUntil])
        }
    }

    @Test
    fun `bulkExtend preserves longer restriction and skips admins and missing`() {
        setupDb()
        val now = System.currentTimeMillis()
        val thirtyDays = now + 30L * 86_400_000L
        transaction {
            Users.insert {
                it[Users.id] = "u2"
                it[Users.name] = "u2"
                it[Users.email] = "u2@test.local"
                it[Users.passwordHash] = "x"
                it[Users.suspendedUntil] = thirtyDays
            }
        }
        AdminAccess.grantAdmin("admin1")
        try {
            val until = now + 7L * 86_400_000L
            val result = UserDispositionService(UserRepository()).bulkExtend(
                actorId = "actor",
                ids = listOf("u1", "u2", "admin1", "missing"),
                field = UserRepository.UserDispositionField.SUSPEND,
                until = until,
                action = "ADMIN_BULK_BAN",
                detailFor = { _ -> "d" },
            )
            assertEquals(listOf("u1", "u2"), result.updated)
            assertEquals(setOf("admin1", "missing"), result.skipped.toSet())
            transaction {
                // u2 的 30 天封禁应保长，不被 7 天缩短
                val u2 = Users.selectAll().where { Users.id eq "u2" }.single()
                assertEquals(thirtyDays, u2[Users.suspendedUntil])
                // u1 从 0 扩展到 7 天
                val u1 = Users.selectAll().where { Users.id eq "u1" }.single()
                assertEquals(until, u1[Users.suspendedUntil])
            }
        } finally {
            AdminAccess.revokeAdmin("admin1")
        }
    }

    @Test
    fun `bulkClear zeroes field for non-admin users`() {
        setupDb()
        val result = UserDispositionService(UserRepository()).bulkClear(
            actorId = "actor",
            ids = listOf("u1"),
            field = UserRepository.UserDispositionField.SUSPEND,
            action = "ADMIN_BULK_UNBAN",
            detail = "cleared",
        )
        assertEquals(listOf("u1"), result.updated)
        transaction {
            val u1 = Users.selectAll().where { Users.id eq "u1" }.single()
            assertEquals(0L, u1[Users.suspendedUntil])
        }
    }
}
