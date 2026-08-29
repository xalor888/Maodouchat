package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.ChatType
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationCreationServiceTest {
    private var database: Database? = null

    private fun setupDb() {
        val url = "jdbc:h2:mem:conversation-creation-service-${COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(url, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("owner", "member", "blocked").forEach { userId ->
                Users.insert {
                    it[id] = userId
                    it[name] = userId
                    it[email] = "$userId@test.local"
                    it[passwordHash] = "x"
                }
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `explicit group type creates a group regardless of legacy flag`() {
        setupDb()

        val outcome = service().create(
            actorId = "owner",
            command = CreateConversationCommand(listOf("member"), false, "Team", ChatType.GROUP),
            maxGroupMembers = 20,
            maxChannelMembers = 100,
        )

        assertEquals(CreateConversationResult.CREATED, outcome.result)
        val chatId = assertNotNull(outcome.conversationId)
        transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.single()
            assertTrue(chat[Chats.isGroup])
            assertEquals(ChatType.GROUP, chat[Chats.chatType])
            assertEquals(listOf("owner"), ChatParticipants.selectAll().map { it[ChatParticipants.userId] })
            assertEquals("member", GroupInvitations.selectAll().single()[GroupInvitations.userId])
        }
    }

    @Test
    fun `direct type cannot be persisted with group flag`() {
        setupDb()

        val outcome = service().create(
            actorId = "owner",
            command = CreateConversationCommand(listOf("member"), true, null, ChatType.DIRECT),
            maxGroupMembers = 20,
            maxChannelMembers = 100,
        )

        assertEquals(CreateConversationResult.INVALID_TYPE_SHAPE, outcome.result)
        transaction {
            assertEquals(0L, Chats.selectAll().count())
            assertEquals(0L, ChatParticipants.selectAll().count())
        }
    }

    @Test
    fun `blocked initial invite rolls back whole group creation`() {
        setupDb()
        transaction {
            BlockedUsers.insert {
                it[blockerId] = "blocked"
                it[blockedId] = "owner"
            }
        }

        val outcome = service().create(
            actorId = "owner",
            command = CreateConversationCommand(listOf("blocked"), true, "Nope", ChatType.GROUP),
            maxGroupMembers = 20,
            maxChannelMembers = 100,
        )

        assertEquals(CreateConversationResult.PARTICIPANT_BLOCKED, outcome.result)
        transaction {
            assertEquals(0L, Chats.selectAll().count())
            assertEquals(0L, GroupInvitations.selectAll().count())
        }
    }

    @Test
    fun `missing initial invitee leaves no partial group`() {
        setupDb()

        val outcome = service().create(
            actorId = "owner",
            command = CreateConversationCommand(listOf("missing"), true, "Nope", null),
            maxGroupMembers = 20,
            maxChannelMembers = 100,
        )

        assertEquals(CreateConversationResult.PARTICIPANT_NOT_FOUND, outcome.result)
        assertEquals("missing", outcome.missingUserId)
        transaction {
            assertEquals(0L, Chats.selectAll().count())
            assertEquals(0L, GroupInvitations.selectAll().count())
        }
    }

    private fun service() = ConversationCreationService(
        ConversationCreationRepository(),
        GroupInvitationRepository(),
    )

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
