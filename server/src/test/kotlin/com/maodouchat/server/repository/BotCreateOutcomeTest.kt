package com.maodouchat.server.repository

import com.maodouchat.server.db.AiPreferences
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.SenderKeyDistributions
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `deleting bot clears its personal metadata`() {
        setupDb()
        val created = BotRepository.create("u1", "Cleanup Bot", "bot_cleanup", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val now = System.currentTimeMillis()
        transaction {
            Chats.insert {
                it[Chats.id] = "c1"
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "c1"
                it[ChatParticipants.userId] = botId
                it[ChatParticipants.joinedAt] = now
            }
            Messages.insert {
                it[Messages.id] = "m1"
                it[Messages.chatId] = "c1"
                it[Messages.senderId] = botId
                it[Messages.content] = "x"
                it[Messages.type] = "TEXT"
                it[Messages.timestamp] = now
            }
            MessageReactions.insert {
                it[MessageReactions.messageId] = "m1"
                it[MessageReactions.userId] = botId
                it[MessageReactions.emoji] = "x"
                it[MessageReactions.reactedAt] = now
            }
            ReadReceipts.insert {
                it[ReadReceipts.messageId] = "m1"
                it[ReadReceipts.userId] = botId
                it[ReadReceipts.readAt] = now
            }
            StarMessages.insert {
                it[StarMessages.userId] = botId
                it[StarMessages.messageId] = "m1"
                it[StarMessages.starredAt] = now
            }
            SenderKeyDistributions.insert {
                it[SenderKeyDistributions.id] = "skd_1"
                it[SenderKeyDistributions.chatId] = "c1"
                it[SenderKeyDistributions.epoch] = 1
                it[SenderKeyDistributions.senderId] = botId
                it[SenderKeyDistributions.recipientUserId] = "u1"
                it[SenderKeyDistributions.recipientDeviceId] = 1
                it[SenderKeyDistributions.createdAt] = now
                it[SenderKeyDistributions.updatedAt] = now
            }
            AiPreferences.insert {
                it[AiPreferences.userId] = botId
                it[AiPreferences.scope] = "USER"
                it[AiPreferences.chatId] = ""
                it[AiPreferences.enabled] = true
                it[AiPreferences.updatedAt] = now
            }
            GroupPolls.insert {
                it[GroupPolls.id] = "poll_1"
                it[GroupPolls.chatId] = "c1"
                it[GroupPolls.creatorId] = "u1"
                it[GroupPolls.question] = "Q"
                it[GroupPolls.optionsJson] = """["A","B"]"""
                it[GroupPolls.multi] = false
                it[GroupPolls.anonymous] = false
                it[GroupPolls.closed] = false
                it[GroupPolls.createdAt] = now
            }
            GroupPollVotes.insert {
                it[GroupPollVotes.pollId] = "poll_1"
                it[GroupPollVotes.userId] = botId
                it[GroupPollVotes.optionIndex] = 0
                it[GroupPollVotes.votedAt] = now
            }
        }

        assertTrue(BotRepository.delete(botId, "u1"))
        transaction {
            assertTrue(MessageReactions.selectAll().where { MessageReactions.userId eq botId }.empty())
            assertTrue(ReadReceipts.selectAll().where { ReadReceipts.userId eq botId }.empty())
            assertTrue(StarMessages.selectAll().where { StarMessages.userId eq botId }.empty())
            assertTrue(
                SenderKeyDistributions.selectAll().where {
                    (SenderKeyDistributions.senderId eq botId) or
                        (SenderKeyDistributions.recipientUserId eq botId)
                }.empty()
            )
            assertTrue(AiPreferences.selectAll().where { AiPreferences.userId eq botId }.empty())
            assertTrue(GroupPollVotes.selectAll().where { GroupPollVotes.userId eq botId }.empty())
        }
    }

    @Test
    fun `enqueueUpdate refuses disabled or suspended-owner bots`() {
        setupDb()
        val created = BotRepository.create("u1", "Inbox Bot", "inbox_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id

        BotRepository.enqueueUpdate(botId, """{"event":"chat"}""")
        transaction {
            assertEquals(
                1L,
                BotUpdateInbox.selectAll().where { BotUpdateInbox.botId eq botId }.count()
            )
        }

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        BotRepository.enqueueUpdate(botId, """{"event":"must_drop"}""")
        transaction {
            assertEquals(
                1L,
                BotUpdateInbox.selectAll().where { BotUpdateInbox.botId eq botId }.count()
            )
        }

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = 0
            }
            BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.enabled] = false
            }
        }

        BotRepository.enqueueUpdate(botId, """{"event":"disabled"}""")
        transaction {
            assertEquals(
                1L,
                BotUpdateInbox.selectAll().where { BotUpdateInbox.botId eq botId }.count()
            )
        }
    }

    @Test
    fun `token-auth mutations revalidate owner and enabled state`() {
        setupDb()
        val created = BotRepository.create("u1", "Mutation Bot", "mutation_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        assertTrue(BotRepository.setWebhookByToken(botId, "https://example.com/hook") == null)
        assertTrue(BotRepository.setMyName(botId, "blocked-name") == null)
        assertTrue(BotRepository.setMyDescription(botId, "blocked-description") == null)
        assertTrue(
            BotRepository.setMyCommands(
                botId,
                listOf(BotRepository.BotCommandDef(command = "help", description = "help"))
            ) == null
        )
        assertTrue(!BotRepository.clearMyCommands(botId))

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = 0
            }
            BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.enabled] = false
            }
        }

        assertTrue(BotRepository.setWebhookByToken(botId, "https://example.com/hook") == null)
        assertTrue(BotRepository.setMyName(botId, "disabled-name") == null)
    }

    @Test
    fun `inbox polling and deletion revalidate owner and enabled state`() {
        setupDb()
        val created = BotRepository.create("u1", "Poll Bot", "poll_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id

        BotRepository.enqueueUpdate(botId, """{"event":"poll"}""")
        assertEquals(1L, BotRepository.countPendingUpdates(botId))
        assertEquals(1, BotRepository.getUpdates(botId).size)

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        assertEquals(0L, BotRepository.countPendingUpdates(botId))
        assertTrue(BotRepository.getUpdates(botId).isEmpty())
        assertEquals(0, BotRepository.deleteUpdates(botId, Long.MAX_VALUE))

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = 0
            }
            BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.enabled] = false
            }
        }

        assertEquals(0L, BotRepository.countPendingUpdates(botId))
        assertTrue(BotRepository.getUpdates(botId).isEmpty())
        assertEquals(0, BotRepository.deleteUpdates(botId, Long.MAX_VALUE))
    }
}
