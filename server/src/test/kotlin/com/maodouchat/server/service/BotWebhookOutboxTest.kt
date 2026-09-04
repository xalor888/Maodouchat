package com.maodouchat.server.service

import com.maodouchat.server.db.BotWebhookOutbox
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B12：webhook outbox worker lease 的原子抢占、过期回收与终态保护。
 */
class BotWebhookOutboxTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:webhook-outbox-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

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
    fun `fresh pending row is claimable and second claim is rejected while leased`() {
        setupDb()
        val id = "wh_test_a"
        BotWebhookService.insertOutbox(id, "bot_1", "https://example.com/hook", "tok", """{"e":1}""", 1L)

        assertTrue(BotWebhookService.claimLease(id))
        // 活跃租约期内其他 worker 不得抢占
        assertFalse(BotWebhookService.claimLease(id))

        val row = transaction {
            BotWebhookOutbox.selectAll().where { BotWebhookOutbox.id eq id }.single()
        }
        assertNotNull(row[BotWebhookOutbox.leaseOwner])
        assertTrue(row[BotWebhookOutbox.leaseUntil] > System.currentTimeMillis())
        assertEquals("PENDING", row[BotWebhookOutbox.status])
    }

    @Test
    fun `expired lease is reclaimable after crash`() {
        setupDb()
        val id = "wh_test_b"
        BotWebhookService.insertOutbox(id, "bot_2", "https://example.com/hook", "tok", """{"e":2}""", 2L)
        assertTrue(BotWebhookService.claimLease(id))

        // 模拟崩溃 worker：租约已过期
        transaction {
            BotWebhookOutbox.update({ BotWebhookOutbox.id eq id }) {
                it[BotWebhookOutbox.leaseUntil] = System.currentTimeMillis() - 1L
            }
        }
        assertTrue(BotWebhookService.claimLease(id))
    }

    @Test
    fun `terminal rows are never claimable`() {
        setupDb()
        val delivered = "wh_test_c"
        BotWebhookService.insertOutbox(delivered, "bot_3", "https://example.com/hook", "tok", """{"e":3}""", 3L)
        assertTrue(BotWebhookService.claimLease(delivered))
        BotWebhookService.finalizeOutbox(delivered, "DELIVERED", 1)
        assertFalse(BotWebhookService.claimLease(delivered))

        val dead = "wh_test_d"
        BotWebhookService.insertOutbox(dead, "bot_4", "https://example.com/hook", "tok", """{"e":4}""", 4L)
        assertTrue(BotWebhookService.claimLease(dead))
        BotWebhookService.finalizeOutbox(dead, "DEAD", 3)
        assertFalse(BotWebhookService.claimLease(dead))
    }

    @Test
    fun `finalize only mutates row owned by this worker`() {
        setupDb()
        val id = "wh_test_e"
        BotWebhookService.insertOutbox(id, "bot_5", "https://example.com/hook", "tok", """{"e":5}""", 5L)
        assertTrue(BotWebhookService.claimLease(id))

        // 模拟租约被其他 worker 抢占
        transaction {
            BotWebhookOutbox.update({ BotWebhookOutbox.id eq id }) {
                it[BotWebhookOutbox.leaseOwner] = "other-worker"
            }
        }
        // 本 worker 的 finalize 不应影响他人持有的行
        BotWebhookService.finalizeOutbox(id, "DELIVERED", 1)
        val status = transaction {
            BotWebhookOutbox.selectAll().where { BotWebhookOutbox.id eq id }.single()[BotWebhookOutbox.status]
        }
        assertEquals("PENDING", status)
    }
}
