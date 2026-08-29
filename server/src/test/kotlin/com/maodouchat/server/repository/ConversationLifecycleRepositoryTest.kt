package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.insertMessagingV2MessageFixture
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationLifecycleRepositoryTest {
    private var database: Database? = null

    private fun setupDb() {
        val url = "jdbc:h2:mem:conversation-lifecycle-${COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(url, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("owner", "member").forEach { userId ->
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
    fun `last member can delete group with pending invitations`() {
        setupDb()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("owner"),
            isGroup = true,
            groupName = "Pending invite group",
            creatorId = "owner",
        )
        assertEquals(
            GroupMemberMutationResult.UPDATED,
            GroupInvitationRepository().inviteMembers(
                group.id,
                "owner",
                listOf("member"),
                maxMembers = 10,
            ).result,
        )

        val outcome = ConversationLifecycleRepository().leave(group.id, "owner")

        assertEquals(LeaveConversationResult.LEFT, outcome.result)
        assertTrue(outcome.conversationDeleted)
        assertEquals(2, outcome.memberRevisionAfter)
        assertEquals(listOf("owner"), outcome.recipientsBefore)
        transaction {
            assertTrue(Chats.selectAll().where { Chats.id eq group.id }.empty())
            assertTrue(GroupInvitations.selectAll().where { GroupInvitations.chatId eq group.id }.empty())
            assertTrue(ChatParticipants.selectAll().where { ChatParticipants.chatId eq group.id }.empty())
        }
    }

    @Test
    fun `channel deletion preserves revision broadcast snapshot after row deletion`() {
        setupDb()
        val channel = ConversationCreationRepository().create(
            participantIds = listOf("owner", "member"),
            isGroup = true,
            groupName = "Channel",
            creatorId = "owner",
            chatType = ChatType.CHANNEL,
        )

        val outcome = ConversationLifecycleRepository().leave(channel.id, "owner")

        assertEquals(LeaveConversationResult.LEFT, outcome.result)
        assertTrue(outcome.conversationDeleted)
        assertEquals(2, outcome.memberRevisionAfter)
        assertEquals(setOf("owner", "member"), outcome.recipientsBefore.toSet())
        assertNull(ConversationQueryRepository().getById(channel.id))
    }

    @Test
    fun `member leave deletes only that members durable mailbox`() {
        setupDb()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("owner", "member"),
            isGroup = true,
            groupName = "Mailbox cleanup",
            creatorId = "owner",
        )
        transaction {
            insertMessagingV2MessageFixture(
                messageId = "message-1",
                conversationId = group.id,
                senderUserId = "owner",
                timestamp = 10L,
            )
            listOf("owner", "member").forEachIndexed { index, recipient ->
                MessagingV2Envelopes.insert {
                    it[id] = "envelope-$recipient"
                    it[sequence] = (index + 1).toLong()
                    it[messageId] = "message-1"
                    it[recipientUserId] = recipient
                    it[recipientDeviceId] = 1
                    it[ciphertextType] = "PREKEY"
                    it[ciphertext] = "cipher-$recipient"
                    it[serverTimestamp] = 10L
                    it[acknowledgedAt] = null
                }
            }
        }

        val outcome = ConversationLifecycleRepository().leave(group.id, "member")

        assertEquals(LeaveConversationResult.LEFT, outcome.result)
        assertTrue(!outcome.conversationDeleted)
        transaction {
            assertEquals(
                0L,
                MessagingV2Envelopes.selectAll().where {
                    MessagingV2Envelopes.recipientUserId eq "member"
                }.count(),
            )
            assertEquals(
                1L,
                MessagingV2Envelopes.selectAll().where {
                    MessagingV2Envelopes.recipientUserId eq "owner"
                }.count(),
            )
            assertEquals(
                1L,
                MessagingV2Messages.selectAll().where {
                    MessagingV2Messages.id eq "message-1"
                }.count(),
            )
        }
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
