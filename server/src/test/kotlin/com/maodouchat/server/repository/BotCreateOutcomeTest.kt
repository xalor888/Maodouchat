package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Bot 创建失败必须区分“用户名占用/非法/数量上限”，不能全部静默折叠成 null。
 */
class BotCreateOutcomeTest {

    private val dbUrl =
        "jdbc:h2:mem:bot-create-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

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
    fun `duplicate username is reported distinctly from invalid input`() {
        setupDb()
        val first = BotRepository.create("u1", "Test Bot", "test_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(first)

        val duplicate = BotRepository.create("u1", "Test Bot", "TEST_BOT", "description")
        assertEquals(BotRepository.BotCreateResult.UsernameTaken, duplicate)

        val invalid = BotRepository.create("u1", "Test Bot", "1bad", "description")
        assertEquals(BotRepository.BotCreateResult.InvalidInput, invalid)
    }
}
