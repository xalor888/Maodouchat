package com.maodouchat.server.repository

import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupInviteMembersTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:group-invite-members-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("u1", "u2", "u3").forEach { uid ->
                Users.insert {
                    it[id] = uid
                    it[Users.name] = uid
                    it[Users.email] = "$uid@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `declined invite can be reopened without unique index conflict`() {
        setupDb()
        val repo = GroupInvitationRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "reopen",
            creatorId = "u1"
        )
        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED,
            repo.inviteMembers(group.id, "u1", listOf("u2"), maxMembers = 10).result
        )
        val inviteId = repo.listForChat(group.id).single { it.userId == "u2" }.id
        assertTrue(repo.decline(inviteId, "u2"))
        val reopen = repo.inviteMembers(group.id, "u1", listOf("u2"), maxMembers = 10)
        assertEquals(com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED, reopen.result)
        assertEquals(listOf("u2"), reopen.invitedUserIds)
        transaction {
            val rows = GroupInvitations.selectAll().where {
                (GroupInvitations.chatId eq group.id) and (GroupInvitations.userId eq "u2")
            }.toList()
            assertEquals(1, rows.size)
            assertEquals("PENDING", rows.single()[GroupInvitations.status])
        }
    }

    @Test
    fun `member limit counts all pending invitations in the group`() {
        setupDb()
        val repo = GroupInvitationRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "cap",
            creatorId = "u1"
        )
        assertEquals(
            com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED,
            repo.inviteMembers(group.id, "u1", listOf("u2"), maxMembers = 2).result
        )
        val overflow = repo.inviteMembers(group.id, "u1", listOf("u3"), maxMembers = 2)
        assertEquals(com.maodouchat.server.repository.GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED, overflow.result)
    }

    @Test
    fun `missing invitee id is returned on USER_NOT_FOUND`() {
        setupDb()
        val repo = GroupInvitationRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "missing",
            creatorId = "u1"
        )
        val missing = repo.inviteMembers(group.id, "u1", listOf("ghost"), maxMembers = 10)
        assertEquals(com.maodouchat.server.repository.GroupMemberMutationResult.USER_NOT_FOUND, missing.result)
        assertEquals("ghost", missing.missingUserId)
    }

    @Test
    fun `invite token join commits membership usage revision and audit together`() {
        setupDb()
        val repo = GroupInvitationRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "token",
            creatorId = "u1",
        )
        val tokenState = repo.configureToken(
            chatId = group.id,
            actorId = "u1",
            rotate = true,
            expiresAt = System.currentTimeMillis() + 60_000,
            maxUses = 3,
        ).invite!!

        val joined = repo.consumeToken(tokenState.token, "u2", maxMembers = 10)!!

        assertTrue(joined.newlyJoined)
        assertEquals(group.id, joined.chatId)
        assertEquals(2, joined.memberRevisionAfter)
        assertEquals(setOf("u1", "u2"), joined.recipientsAfter.toSet())
        transaction {
            val chat = Chats.selectAll().where { Chats.id eq group.id }.single()
            assertEquals(2, chat[Chats.memberRevision])
            assertEquals(1, chat[Chats.groupInviteUseCount])
            assertEquals(
                1L,
                ChatParticipants.selectAll().where {
                    (ChatParticipants.chatId eq group.id) and
                        (ChatParticipants.userId eq "u2")
                }.count(),
            )
            val joinedAudits = GroupAuditLogs.selectAll().where {
                (GroupAuditLogs.chatId eq group.id) and
                    (GroupAuditLogs.action eq "MEMBER_JOINED")
            }.toList()
            assertEquals(1, joinedAudits.size)
            assertEquals("u2", joinedAudits.single()[GroupAuditLogs.targetUserId])
        }
    }

    @Test
    fun `accept invitation returns committed revision and recipients`() {
        setupDb()
        val repo = GroupInvitationRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "accept snapshot",
            creatorId = "u1",
        )
        assertEquals(
            GroupMemberMutationResult.UPDATED,
            repo.inviteMembers(group.id, "u1", listOf("u2"), maxMembers = 10).result,
        )
        val inviteId = repo.listForChat(group.id).single().id

        val accepted = repo.accept(inviteId, "u2", maxMembers = 10)

        assertEquals(GroupInviteAcceptResult.ACCEPTED, accepted.result)
        assertEquals(group.id, accepted.chatId)
        assertEquals(2, accepted.memberRevisionAfter)
        assertEquals(setOf("u1", "u2"), accepted.recipientsAfter.toSet())
    }
}
