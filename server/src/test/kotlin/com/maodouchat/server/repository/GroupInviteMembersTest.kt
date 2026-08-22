package com.maodouchat.server.repository

import com.maodouchat.server.db.GroupInvitations
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
        val repo = ChatRepository()
        val group = repo.createChat(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "reopen",
            creatorId = "u1"
        )
        assertEquals(
            ChatRepository.GroupMemberMutationResult.UPDATED,
            repo.inviteGroupMembers(group.id, "u1", listOf("u2"), maxMembers = 10).result
        )
        val inviteId = repo.listChatGroupInvitations(group.id).single { it.userId == "u2" }.id
        assertTrue(repo.declineGroupInvitation(inviteId, "u2"))
        val reopen = repo.inviteGroupMembers(group.id, "u1", listOf("u2"), maxMembers = 10)
        assertEquals(ChatRepository.GroupMemberMutationResult.UPDATED, reopen.result)
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
        val repo = ChatRepository()
        val group = repo.createChat(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "cap",
            creatorId = "u1"
        )
        assertEquals(
            ChatRepository.GroupMemberMutationResult.UPDATED,
            repo.inviteGroupMembers(group.id, "u1", listOf("u2"), maxMembers = 2).result
        )
        val overflow = repo.inviteGroupMembers(group.id, "u1", listOf("u3"), maxMembers = 2)
        assertEquals(ChatRepository.GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED, overflow.result)
    }

    @Test
    fun `missing invitee id is returned on USER_NOT_FOUND`() {
        setupDb()
        val repo = ChatRepository()
        val group = repo.createChat(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "missing",
            creatorId = "u1"
        )
        val missing = repo.inviteGroupMembers(group.id, "u1", listOf("ghost"), maxMembers = 10)
        assertEquals(ChatRepository.GroupMemberMutationResult.USER_NOT_FOUND, missing.result)
        assertEquals("ghost", missing.missingUserId)
    }
}
