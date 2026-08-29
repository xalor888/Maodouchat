package com.maodouchat.server.repository

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bot 创建失败必须区分“用户名占用/非法/数量上限”，不能全部静默折叠成 null。
 */
class BotCreateOutcomeTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:bot-create-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

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
    fun `duplicate username is reported distinctly from invalid input`() {
        setupDb()
        val first = BotRepository.create("u1", "Test Bot", "test_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(first)
        val seeded = BotRepository.getMyCommands(first.bot.id).map { it.command }.toSet()
        assertEquals(setOf("start", "help"), seeded)

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
            insertV2Message("m1", "c1", botId, now)
            ServiceMessageReactions.insert {
                it[messageId] = "m1"
                it[botUserId] = botId
                it[emoji] = "x"
                it[reactedAt] = now
            }
            StarMessages.insert {
                it[StarMessages.userId] = botId
                it[StarMessages.messageId] = "m1"
                it[StarMessages.starredAt] = now
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
            assertTrue(ServiceMessageReactions.selectAll().where { ServiceMessageReactions.botUserId eq botId }.empty())
            assertTrue(StarMessages.selectAll().where { StarMessages.userId eq botId }.empty())
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
    fun `getMyCommands hides command menu after owner or bot becomes unavailable`() {
        setupDb()
        val created = BotRepository.create("u1", "Command Bot", "command_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val commands = listOf(BotRepository.BotCommandDef(command = "help", description = "show help"))

        assertTrue(BotRepository.setMyCommands(botId, commands) != null)
        assertTrue(BotRepository.getMyCommands(botId).isNotEmpty())

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }
        assertTrue(BotRepository.getMyCommands(botId).isEmpty())

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = 0
            }
            BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.enabled] = false
            }
        }
        assertTrue(BotRepository.getMyCommands(botId).isEmpty())
    }

    @Test
    fun `service message insert revalidates owner state inside transaction`() {
        setupDb()
        val created = BotRepository.create("u1", "Message Bot", "message_bot", "description")
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
        }

        val serviceMessageRepo = ServiceMessageRepository()
        assertTrue(serviceMessageRepo.insert("m1", "c1", botId, "hello", now))

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }
        assertFalse(serviceMessageRepo.insert("m2", "c1", botId, "blocked", now))
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

    @Test
    fun `group poll writes revalidate bot owner state inside transactions`() {
        setupDb()
        val created = BotRepository.create("u1", "Poll Write Bot", "poll_write_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val now = System.currentTimeMillis()
        transaction {
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "Poll Group"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
                it[Chats.groupInviteToken] = "invite-token-00000000000000000000000000"
                it[Chats.groupInviteExpiresAt] = now + 60_000L
                it[Chats.groupInviteMaxUses] = 1
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "g1"
                it[ChatParticipants.userId] = botId
                it[ChatParticipants.role] = "ADMIN"
                it[ChatParticipants.joinedAt] = now
            }
        }

        val poll = GroupPlayRepository.createPoll(
            chatId = "g1",
            creatorId = botId,
            question = "Release scope?",
            options = listOf("Android only", "Desktop later"),
            multi = false,
            anonymous = false,
            closesAt = null,
            requireBotDeliverable = true
        )!!
        assertTrue(
            GroupPlayRepository.vote(
                pollId = poll.id,
                userId = botId,
                optionIndexes = listOf(0),
                requireBotDeliverable = true
            ) != null
        )

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        assertFalse(BotRepository.isBotDeliverable(botId))
        assertNull(
            GroupPlayRepository.createPoll(
                chatId = "g1",
                creatorId = botId,
                question = "Blocked?",
                options = listOf("Yes", "No"),
                multi = false,
                anonymous = false,
                closesAt = null,
                requireBotDeliverable = true
            )
        )
        assertNull(
            GroupPlayRepository.vote(
                pollId = poll.id,
                userId = botId,
                optionIndexes = listOf(1),
                requireBotDeliverable = true
            )
        )
        assertNull(
            GroupPlayRepository.closePoll(
                pollId = poll.id,
                userId = botId,
                requireBotDeliverable = true
            )
        )
    }

    @Test
    fun `pin writes revalidate bot owner state inside transactions`() {
        setupDb()
        val created = BotRepository.create("u1", "Pin Write Bot", "pin_write_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val now = System.currentTimeMillis()
        transaction {
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "Pin Group"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
                it[Chats.groupInviteToken] = "invite-token-00000000000000000000000000"
                it[Chats.groupInviteExpiresAt] = now + 60_000L
                it[Chats.groupInviteMaxUses] = 1
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "g1"
                it[ChatParticipants.userId] = botId
                it[ChatParticipants.role] = "ADMIN"
                it[ChatParticipants.joinedAt] = now
            }
            listOf("m1", "m2").forEach { messageId ->
                insertV2Message(messageId, "g1", botId, now)
            }
        }

        val repo = PinnedMessageRepository()
        assertEquals(
            PinnedMessageRepository.PinResult.PINNED,
            repo.toggle("g1", "m1", botId, actorIsManager = true, requireBotDeliverable = true).result
        )

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        assertEquals(
            PinnedMessageRepository.PinResult.FORBIDDEN,
            repo.toggle("g1", "m2", botId, actorIsManager = true, requireBotDeliverable = true).result
        )
        assertEquals(
            PinnedMessageRepository.PinResult.FORBIDDEN,
            repo.clearAll("g1", botId, actorIsManager = true, requireBotDeliverable = true).result
        )
    }

    @Test
    fun `group management writes revalidate bot owner state inside transactions`() {
        setupDb()
        val created = BotRepository.create("u1", "Group Write Bot", "group_write_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val now = System.currentTimeMillis()
        transaction {
            Users.insert {
                it[Users.id] = "u2"
                it[Users.name] = "u2"
                it[Users.email] = "u2@test.local"
                it[Users.passwordHash] = "x"
            }
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "Group Write"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
                it[Chats.groupInviteToken] = "invite-token-00000000000000000000000000"
                it[Chats.groupInviteExpiresAt] = now + 60_000L
                it[Chats.groupInviteMaxUses] = 1
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "g1"
                it[ChatParticipants.userId] = botId
                it[ChatParticipants.role] = "ADMIN"
                it[ChatParticipants.joinedAt] = now
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = "g1"
                it[ChatParticipants.userId] = "u2"
                it[ChatParticipants.role] = "MEMBER"
                it[ChatParticipants.joinedAt] = now
            }
        }

        val groupModerationRepo = GroupModerationRepository()
        val groupProfileRepo = GroupProfileRepository()
        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED,
            groupModerationRepo.updateMemberMute(
                chatId = "g1",
                actorId = botId,
                targetUserId = "u2",
                mutedUntil = now + 60_000L,
                requireBotDeliverable = true
            )
        )

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN,
            groupModerationRepo.updateMemberMute(
                chatId = "g1",
                actorId = botId,
                targetUserId = "u2",
                mutedUntil = 0L,
                requireBotDeliverable = true
            )
        )
        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN,
            GroupMembershipRepository().removeMember(
                chatId = "g1",
                actorId = botId,
                targetUserId = "u2",
                requireBotDeliverable = true
            )
        )
        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN,
            groupProfileRepo.updateName(
                chatId = "g1",
                actorId = botId,
                name = "Blocked",
                requireBotDeliverable = true
            )
        )
        assertEquals(
            GroupBulkMuteResult(GroupMemberMutationResult.FORBIDDEN),
            groupModerationRepo.updateMembersMute(
                chatId = "g1",
                actorId = botId,
                targetUserIds = listOf("u2"),
                mutedUntil = now + 120_000L,
                requireBotDeliverable = true
            )
        )
        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN,
            GroupInvitationRepository().configureToken(
                chatId = "g1",
                actorId = botId,
                rotate = true,
                expiresAt = now + 3600_000L,
                maxUses = 10,
                requireBotDeliverable = true
            ).result
        )
    }

    @Test
    fun `message mutations revalidate bot owner state inside transactions`() {
        setupDb()
        val created = BotRepository.create("u1", "Message Mutation Bot", "message_mutation_bot", "description")
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
        }

        val messageRepo = ServiceMessageRepository()
        val starMessageRepo = StarMessageRepository()
        assertTrue(messageRepo.insert("m_ok", "c1", botId, "hello", now))
        assertTrue(messageRepo.insert("m_blocked", "c1", botId, "hello", now))
        transaction {
            insertV2Message("m_ok", "c1", botId, now)
            insertV2Message("m_blocked", "c1", botId, now)
        }

        assertNotNull(messageRepo.editOwn("m_ok", botId, "caption", now + 1L))
        assertNotNull(messageRepo.setReaction("m_ok", botId, "x"))
        assertTrue(starMessageRepo.toggleStar(botId, "m_ok", requireBotDeliverable = true) == true)
        assertNotNull(messageRepo.deleteOwn("m_ok", botId))

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }

        assertNull(messageRepo.editOwn("m_blocked", botId, "blocked", now + 2L))
        assertNull(messageRepo.setReaction("m_blocked", botId, "y"))
        assertNull(starMessageRepo.toggleStar(botId, "m_blocked", requireBotDeliverable = true))
        assertNull(messageRepo.deleteOwn("m_blocked", botId))
    }

    @Test
    fun `command logging revalidates bot owner and enabled state`() {
        setupDb()
        val created = BotRepository.create("u1", "Command Log Bot", "command_log_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id

        BotRepository.logCommand(botId, null, null, "test")
        transaction {
            assertEquals(1L, BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }.count())
        }

        val suspendedUntil = System.currentTimeMillis() + 60_000
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = suspendedUntil
            }
        }
        BotRepository.logCommand(botId, null, null, "blocked")
        transaction {
            assertEquals(1L, BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }.count())
        }

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.suspendedUntil] = 0
            }
            BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.enabled] = false
            }
        }
        BotRepository.logCommand(botId, null, null, "disabled")
        transaction {
            assertEquals(1L, BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }.count())
        }
    }

    @Test
    fun `user command inbox rejects ciphertext and accepts slash`() {
        setupDb()
        val created = BotRepository.create("u1", "Inbox Bot", "inbox_bot", "description")
        assertIs<BotRepository.BotCreateResult.Success>(created)
        val botId = created.bot.id
        val now = System.currentTimeMillis()
        transaction {
            Users.insert {
                it[id] = "u2"
                it[Users.name] = "u2"
                it[Users.email] = "u2@test.local"
                it[Users.passwordHash] = "x"
            }
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.groupName] = "g"
            }
            listOf("u1", "u2", botId).forEach { uid ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "g1"
                    it[ChatParticipants.userId] = uid
                    it[ChatParticipants.joinedAt] = now
                }
            }
        }
        assertTrue(
            BotRepository.enqueueUserCommand(
                chatId = "g1",
                userId = "u1",
                text = """{"ciphertext":"abc"}"""
            ).isEmpty()
        )
        assertTrue(
            BotRepository.enqueueUserCommand(
                chatId = "g1",
                userId = "u1",
                text = "hello everyone"
            ).isEmpty()
        )
        val delivered = BotRepository.enqueueUserCommand(
            chatId = "g1",
            userId = "u1",
            text = "/help tomorrow"
        )
        assertEquals(1, delivered.size)
        assertEquals(botId, delivered.first().first)
        assertTrue(delivered.first().second.contains("\"command\":\"help\""))
        val updates = BotRepository.getUpdates(botId)
        assertEquals(1, updates.size)
        assertTrue(updates.first().second.contains("user_command"))
        assertFalse(updates.first().second.contains("ciphertext"))
    }

    private fun insertV2Message(messageId: String, chatId: String, senderId: String, timestamp: Long) {
        MessagingV2Messages.insert {
            it[id] = messageId
            it[conversationId] = chatId
            it[senderUserId] = senderId
            it[senderDeviceId] = 0
            it[kind] = "SERVICE"
            it[recordClass] = MessagingV2RecordClass.MESSAGE
            it[groupRevision] = null
            it[clientTimestamp] = timestamp
            it[serverTimestamp] = timestamp
            it[requestDigest] = "test-$messageId"
        }
    }
}
