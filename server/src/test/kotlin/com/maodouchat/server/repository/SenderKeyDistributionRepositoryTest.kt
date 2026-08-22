package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.SenderKeyDistributionTargetRequest
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
 * SKD coverage: unknown statuses must not be promoted to SENT, and GET with
 * `expectedTargets` must drop historical rows for removed devices while
 * synthesizing PENDING for current devices that were never reported.
 */
class SenderKeyDistributionRepositoryTest {

    private var database: Database? = null

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:skd-repo-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "skd"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `unknown status is stored as PENDING not SENT`() {
        setupDb()
        val repo = SenderKeyDistributionRepository()
        repo.record(
            chatId = "g1",
            epoch = 7L,
            senderId = "u1",
            messageId = "m_skd",
            targets = listOf(
                SenderKeyDistributionTargetRequest("u2", 1, status = "SENT"),
                SenderKeyDistributionTargetRequest("u2", 2, status = "BOGUS"),
                SenderKeyDistributionTargetRequest("u2", 3, status = "failed"),
            )
        )

        val status = repo.getStatus(chatId = "g1", senderId = "u1", epoch = 7L)
        assertEquals(3, status.total)
        assertEquals(1, status.sent)
        assertEquals(1, status.failed)
        assertEquals(1, status.pending)
        val byDevice = status.targets.associateBy { it.deviceId }
        assertEquals("SENT", byDevice[1]?.status)
        assertEquals("PENDING", byDevice[2]?.status)
        assertEquals("FAILED", byDevice[3]?.status)
    }

    @Test
    fun `expectedTargets drops removed devices and synthesizes uncovered current devices`() {
        setupDb()
        val repo = SenderKeyDistributionRepository()
        repo.record(
            chatId = "g1",
            epoch = 9L,
            senderId = "u1",
            messageId = "m_skd_cov",
            targets = listOf(
                SenderKeyDistributionTargetRequest("u2", 1, status = "SENT"),
                SenderKeyDistributionTargetRequest("u2", 2, status = "SENT"),
            )
        )

        val withoutFilter = repo.getStatus(chatId = "g1", senderId = "u1", epoch = 9L)
        assertEquals(setOf(1, 2), withoutFilter.targets.map { it.deviceId }.toSet())

        val coverage = repo.getStatus(
            chatId = "g1",
            senderId = "u1",
            epoch = 9L,
            expectedTargets = setOf("u2" to 1, "u2" to 3)
        )
        assertEquals(2, coverage.total)
        assertEquals(1, coverage.sent)
        assertEquals(1, coverage.pending)
        assertEquals(0, coverage.failed)
        val byDevice = coverage.targets.associateBy { it.deviceId }
        assertTrue(2 !in byDevice, "removed device 2 must not remain in coverage")
        assertEquals("SENT", byDevice[1]?.status)
        assertEquals("PENDING", byDevice[3]?.status)
        assertEquals("device_not_covered", byDevice[3]?.error)
    }
}
