package com.maodouchat.server.service

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B13：AdminIdentity 角色解析（master / moderator / 内容审核权限继承）。
 */
class AdminIdentityTest {

    private var database: Database? = null
    private val dbUrl =
        "jdbc:h2:mem:admin-identity-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[Users.id] = "mod1"
                it[Users.name] = "mod1"
                it[Users.email] = "mod1@test.local"
                it[Users.passwordHash] = "x"
                it[Users.isModerator] = true
            }
            Users.insert {
                it[Users.id] = "plain1"
                it[Users.name] = "plain1"
                it[Users.email] = "plain1@test.local"
                it[Users.passwordHash] = "x"
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        AdminAccess.revokeAdmin("master1")
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `master inherits content moderation access`() {
        setupDb()
        AdminAccess.grantAdmin("master1")
        val identity = AdminIdentityResolver.resolve("master1", UserRepository())
        assertTrue(identity.isMaster)
        assertFalse(identity.isModerator)
        assertTrue(identity.hasContentModerationAccess)
    }

    @Test
    fun `moderator has content moderation access without being master`() {
        setupDb()
        val identity = AdminIdentityResolver.resolve("mod1", UserRepository())
        assertFalse(identity.isMaster)
        assertTrue(identity.isModerator)
        assertTrue(identity.hasContentModerationAccess)
    }

    @Test
    fun `plain user has no admin access`() {
        setupDb()
        val identity = AdminIdentityResolver.resolve("plain1", UserRepository())
        assertFalse(identity.isMaster)
        assertFalse(identity.isModerator)
        assertFalse(identity.hasContentModerationAccess)
    }
}
