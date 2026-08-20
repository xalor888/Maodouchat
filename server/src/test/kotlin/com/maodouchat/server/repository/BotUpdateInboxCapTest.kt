package com.maodouchat.server.repository

import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 9.237：bot 收件箱积压封顶——停止轮询的 bot 不得让 bot_update_inbox 无限增长。
 * 超限按 FIFO 淘汰最旧，保留最近事件（与 BotRepository.MAX_INBOX_PENDING_PER_BOT=500 对齐）。
 */
class BotUpdateInboxCapTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:bot-inbox-cap-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
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

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `inbox caps at limit and evicts oldest first`() {
        setupDb()
        val created = BotRepository.create("u1", "Cap Bot", "bot_cap", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val total = 505
        repeat(total) { i ->
            BotRepository.enqueueUpdate(botId, """{"seq":$i}""")
        }
        val rows = transaction {
            BotUpdateInbox.selectAll()
                .where { BotUpdateInbox.botId eq botId }
                .orderBy(BotUpdateInbox.id to org.jetbrains.exposed.sql.SortOrder.ASC)
                .map { it[BotUpdateInbox.updateJson] }
        }
        // 积压封顶 500，最旧 5 条（seq 0..4）被淘汰
        assertEquals(500, rows.size)
        assertEquals("""{"seq":5}""", rows.first())
        assertEquals("""{"seq":504}""", rows.last())
    }
}
