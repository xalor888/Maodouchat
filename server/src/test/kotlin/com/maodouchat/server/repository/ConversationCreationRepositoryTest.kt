package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.ChatType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversationCreationRepositoryTest {
    private var database: Database? = null

    private fun setupDb() {
        val url = "jdbc:h2:mem:conversation-creation-${COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(url, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("u1", "u2", "u3").forEach { userId ->
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
    fun `group creation revalidates blocked participants inside transaction`() {
        setupDb()
        transaction {
            BlockedUsers.insert {
                it[blockerId] = "u2"
                it[blockedId] = "u1"
            }
        }

        assertFailsWith<IllegalArgumentException> {
            ConversationCreationRepository().create(
                participantIds = listOf("u1", "u2"),
                isGroup = true,
                groupName = "blocked",
                creatorId = "u1",
            )
        }
        transaction {
            assertEquals(0L, Chats.selectAll().count())
            assertEquals(0L, ChatParticipants.selectAll().count())
        }
    }

    @Test
    fun `direct and secret conversations are separately idempotent`() {
        setupDb()
        val repo = ConversationCreationRepository()

        val direct = repo.getOrCreateDirect("u1", "u2")
        val repeatedDirect = repo.getOrCreateDirect("u2", "u1")
        val secret = repo.getOrCreateSecret("u1", "u2")
        val repeatedSecret = repo.getOrCreateSecret("u2", "u1")

        assertEquals(direct.id, repeatedDirect.id)
        assertEquals(secret.id, repeatedSecret.id)
        assertNotEquals(direct.id, secret.id)
        transaction {
            assertEquals(ChatType.DIRECT, Chats.selectAll().where { Chats.id eq direct.id }.single()[Chats.chatType])
            assertEquals(ChatType.SECRET, Chats.selectAll().where { Chats.id eq secret.id }.single()[Chats.chatType])
        }
    }

    @Test
    fun `legacy direct conversation is reused and pair mapping is repaired`() {
        setupDb()
        transaction {
            Chats.insert {
                it[id] = "legacy-direct"
                it[isGroup] = false
                it[chatType] = ChatType.DIRECT
            }
            listOf("u1", "u2").forEach { userId ->
                ChatParticipants.insert {
                    it[chatId] = "legacy-direct"
                    it[ChatParticipants.userId] = userId
                    it[role] = "MEMBER"
                }
            }
        }

        val result = ConversationCreationRepository().getOrCreateDirect("u1", "u2")

        assertEquals("legacy-direct", result.id)
        transaction {
            assertEquals(
                "legacy-direct",
                DirectChatPairs.selectAll().where { DirectChatPairs.pairKey eq "u1:u2" }
                    .single()[DirectChatPairs.chatId],
            )
            assertEquals(1L, Chats.selectAll().count())
        }
    }

    @Test
    fun `concurrent direct creation produces one durable conversation`() = runBlocking {
        setupDb()
        val ids = (1..8).map {
            async(Dispatchers.IO) {
                ConversationCreationRepository().getOrCreateDirect("u1", "u2").id
            }
        }.awaitAll()

        assertEquals(1, ids.toSet().size)
        transaction {
            assertEquals(1L, DirectChatPairs.selectAll().count())
            assertEquals(1L, Chats.selectAll().count())
            assertEquals(2L, ChatParticipants.selectAll().count())
            assertTrue(DirectChatPairs.selectAll().single()[DirectChatPairs.chatId] in ids)
        }
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
