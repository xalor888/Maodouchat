package com.maodouchat.server.service

import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B13：运营统计只读 query model 的仪表盘与审计查询行为。
 */
class OperationsQueryServiceTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:ops-query-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `dashboard counts active vs deactivated users`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            Users.insert {
                it[Users.id] = "u1"
                it[Users.name] = "u1"
                it[Users.email] = "u1@test.local"
                it[Users.passwordHash] = "x"
                it[Users.lastSeen] = now
            }
            Users.insert {
                it[Users.id] = "u2"
                it[Users.name] = "u2"
                it[Users.email] = "u2@test.local"
                it[Users.passwordHash] = "x"
                it[Users.deletedAt] = now
            }
        }
        val d = OperationsQueryService.dashboard(now)
        assertEquals(2L, d.totalUsers + d.deactivatedUsers, "2 rows total across active+deactivated")
        assertEquals(1L, d.activeUsers24h)
        assertEquals(1L, d.deactivatedUsers)
    }

    @Test
    fun `audit logs filter by action and free text`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            ModerationAuditLog.insert {
                it[id] = "a1"
                it[actorId] = "admin-1"
                it[action] = "ADMIN_SUSPEND"
                it[detail] = "banned user"
                it[createdAt] = now
            }
            ModerationAuditLog.insert {
                it[id] = "a2"
                it[actorId] = "admin-2"
                it[action] = "ADMIN_SETTINGS_UPDATE"
                it[detail] = "changed config"
                it[createdAt] = now + 1
            }
        }
        val byAction = OperationsQueryService.auditLogs(10, 0, "ADMIN_SUSPEND", null)
        assertEquals(1, byAction.size)
        assertEquals("admin-1", byAction.single().actorId)

        val byText = OperationsQueryService.auditLogs(10, 0, null, "config")
        assertEquals(1, byText.size)
        assertEquals("ADMIN_SETTINGS_UPDATE", byText.single().action)

        val exported = OperationsQueryService.auditLogsExport(10, 0)
        assertEquals(2, exported.size)
        assertTrue(exported[0].createdAt >= exported[1].createdAt, "descending by createdAt")
    }
}
