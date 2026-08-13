package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 管理端按 refresh token 前缀吊销时，过期 token 不得带动活跃会话一起被吊销。
 */
class AuthTokenPrefixRevocationTest {

    private val dbUrl =
        "jdbc:h2:mem:auth-prefix-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[id] = "u1"
                it[Users.name] = "u1"
                it[Users.email] = "u1@test.local"
                it[Users.passwordHash] = "x"
            }
        }
    }

    @Test
    fun `expired refresh token prefix does not revoke active session`() {
        setupDb()
        val repo = AuthTokenRepository()
        val expiredIssued = repo.issueRefreshToken("u1")
        val expiredHash = sha256Hex(expiredIssued.token)
        val now = System.currentTimeMillis()

        transaction {
            RefreshTokens.update({ RefreshTokens.tokenHash eq expiredHash }) {
                it[expiresAt] = now - 1
            }
        }

        val activeIssued = repo.issueRefreshToken("u1", expiredIssued.sessionId)
        val activeHash = sha256Hex(activeIssued.token)

        val result = repo.revokeByHashPrefixWithSessions("u1", expiredHash.take(12))

        assertEquals(0, result.count)
        assertEquals(emptySet<String>(), result.sessionIds)
        transaction {
            val session = AuthSessions.selectAll().where {
                (AuthSessions.id eq expiredIssued.sessionId) and (AuthSessions.userId eq "u1")
            }.first()
            assertNull(session[AuthSessions.revokedAt])
            val activeRow = RefreshTokens.selectAll().where {
                (RefreshTokens.tokenHash eq activeHash) and (RefreshTokens.userId eq "u1")
            }.first()
            assertNull(activeRow[RefreshTokens.revokedAt])
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
