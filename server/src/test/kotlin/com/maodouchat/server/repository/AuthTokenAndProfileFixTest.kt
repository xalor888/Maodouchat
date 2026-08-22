package com.maodouchat.server.repository

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.ClientPrefsUpdateRequest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * XAL-29：登录会话轮换/重用、资料更新、TOTP 已启用守卫、推送 token 长度、偏好写入。
 */
class AuthTokenAndProfileFixTest {

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:auth-profile-fix-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
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
    fun `refresh rotate succeeds then reuse compromises the session`() {
        setupDb()
        val repo = AuthTokenRepository()
        val issued = repo.issueRefreshToken("u1")
        val first = repo.rotateIfEligible(issued.token)
        assertTrue(first is AuthTokenRepository.RotateRefreshResult.Success, first.toString())

        val reuse = repo.rotateIfEligible(issued.token)
        assertTrue(reuse is AuthTokenRepository.RotateRefreshResult.SessionCompromised, reuse.toString())
        reuse as AuthTokenRepository.RotateRefreshResult.SessionCompromised
        assertEquals("u1", reuse.userId)
        assertEquals(issued.sessionId, reuse.sessionId)

        val rotated = first as AuthTokenRepository.RotateRefreshResult.Success
        val afterCompromise = repo.rotateIfEligible(rotated.refreshToken)
        assertTrue(
            afterCompromise is AuthTokenRepository.RotateRefreshResult.InvalidToken,
            afterCompromise.toString()
        )
        transaction {
            val session = AuthSessions.selectAll().where { AuthSessions.id eq issued.sessionId }.first()
            assertNotNull(session[AuthSessions.revokedAt])
        }
    }

    @Test
    fun `logout revokes refresh session so rotate fails`() {
        setupDb()
        val repo = AuthTokenRepository()
        val issued = repo.issueRefreshToken("u1")
        val revoked = repo.revokeAndGetSession(issued.token)
        assertNotNull(revoked)
        assertEquals("u1", revoked.userId)
        assertEquals(issued.sessionId, revoked.sessionId)
        val again = repo.rotateIfEligible(issued.token)
        assertTrue(again is AuthTokenRepository.RotateRefreshResult.InvalidToken, again.toString())
    }

    @Test
    fun `push token accepts 32 to 512 chars bound to active session only`() {
        setupDb()
        val authRepo = AuthTokenRepository()
        val pushRepo = PushTokenRepository()
        val issued = authRepo.issueRefreshToken("u1")
        val longToken = "A".repeat(400)
        assertTrue(pushRepo.register("u1", "dev-1", longToken, "ANDROID", 480, issued.sessionId))
        assertEquals(1, pushRepo.getForUser("u1").size)
        assertEquals(longToken, pushRepo.getForUser("u1").single().token)

        assertFalse(pushRepo.register("u1", "dev-1", "A".repeat(31), "ANDROID", 480, issued.sessionId))
        assertFalse(pushRepo.register("u1", "dev-1", "A".repeat(513), "ANDROID", 480, issued.sessionId))
        assertFalse(pushRepo.register("u1", "dev-1", "A".repeat(256), "ANDROID", 480, "missing-session"))

        assertTrue(authRepo.revokeSession("u1", issued.sessionId))
        assertFalse(pushRepo.register("u1", "dev-1", "B".repeat(256), "ANDROID", 480, issued.sessionId))
        assertTrue(pushRepo.getForUser("u1").isEmpty())
    }

    @Test
    fun `beginTotpSetup refuses to disable already enabled totp`() {
        setupDb()
        val userRepo = UserRepository()
        val setup = userRepo.beginTotpSetup("u1")
        assertNotNull(setup)
        val secret = setup.first
        val now = System.currentTimeMillis()
        val codes = userRepo.confirmTotpSetup("u1", testTotpCode(secret, now))
        assertNotNull(codes)
        assertTrue(userRepo.isTotpEnabled("u1"))

        assertFailsWith<IllegalArgumentException> { userRepo.beginTotpSetup("u1") }
        assertTrue(userRepo.isTotpEnabled("u1"))
    }

    @Test
    fun `updateProfile skips deleted users and client prefs reject missing user`() {
        setupDb()
        val userRepo = UserRepository()
        userRepo.updateProfile("u1", name = "新名字", status = "忙")
        val updated = userRepo.getById("u1")
        assertNotNull(updated)
        assertEquals("新名字", updated.name)
        assertEquals("忙", updated.status)

        val prefs = ClientPrefsRepository()
        val saved = prefs.update("u1", ClientPrefsUpdateRequest(themeMode = "dark"))
        assertEquals("dark", saved.themeMode)
        assertEquals("dark", prefs.get("u1").themeMode)

        assertFailsWith<IllegalArgumentException> {
            prefs.update("missing-user", ClientPrefsUpdateRequest(themeMode = "light"))
        }

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[deletedAt] = System.currentTimeMillis()
            }
        }
        userRepo.updateProfile("u1", name = "幽灵")
        assertNull(userRepo.getById("u1"))
        assertFailsWith<IllegalArgumentException> {
            prefs.update("u1", ClientPrefsUpdateRequest(themeMode = "light"))
        }
    }
}

private const val TOTP_PERIOD_SEC = 30L
private const val TOTP_DIGITS = 6

private fun testTotpCode(secretBase32: String, nowMs: Long = System.currentTimeMillis()): String {
    val cleaned = secretBase32.trim().uppercase().replace("=", "").replace(" ", "")
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var buffer = 0
    var bitsLeft = 0
    val out = ArrayList<Byte>()
    for (ch in cleaned) {
        val idx = alphabet.indexOf(ch)
        check(idx >= 0) { "invalid base32 char: $ch" }
        buffer = (buffer shl 5) or idx
        bitsLeft += 5
        if (bitsLeft >= 8) {
            out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
            bitsLeft -= 8
        }
    }
    val counter = nowMs / 1000L / TOTP_PERIOD_SEC
    val data = ByteBuffer.allocate(8).putLong(counter).array()
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(out.toByteArray(), "HmacSHA1"))
    val hash = mac.doFinal(data)
    val offset = hash.last().toInt() and 0x0f
    val binary =
        ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
    val otp = binary % 10.0.pow(TOTP_DIGITS).toInt()
    return otp.toString().padStart(TOTP_DIGITS, '0')
}
