package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
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

class GroupProfileModerationRepositoryTest {
    private var database: Database? = null

    private fun setupDb() {
        val url = "jdbc:h2:mem:group-profile-moderation-${COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(url, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("owner", "admin", "member-1", "member-2").forEach { userId ->
                Users.insert {
                    it[id] = userId
                    it[name] = userId
                    it[email] = "$userId@test.local"
                    it[passwordHash] = "x"
                }
            }
            Chats.insert {
                it[id] = CHAT_ID
                it[isGroup] = true
                it[chatType] = "GROUP"
                it[groupName] = "Before"
                it[groupAvatar] = "/group-avatars/old.webp"
                it[memberRevision] = 10
            }
            insertParticipant("owner", "OWNER")
            insertParticipant("admin", "ADMIN")
            insertParticipant("member-1", "MEMBER")
            insertParticipant("member-2", "MEMBER")
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `profile mutation updates value revision and audit together`() {
        setupDb()

        val result = GroupProfileRepository().updateName(CHAT_ID, "admin", "After")

        assertEquals(GroupMemberMutationResult.UPDATED, result)
        transaction {
            val chat = Chats.selectAll().where { Chats.id eq CHAT_ID }.single()
            assertEquals("After", chat[Chats.groupName])
            assertEquals(11, chat[Chats.memberRevision])
            val audit = GroupAuditLogs.selectAll().where { GroupAuditLogs.chatId eq CHAT_ID }.single()
            assertEquals("admin", audit[GroupAuditLogs.actorId])
            assertEquals("GROUP_RENAMED", audit[GroupAuditLogs.action])
        }
    }

    @Test
    fun `forbidden profile mutation leaves all persisted state unchanged`() {
        setupDb()

        val result = GroupProfileRepository().updateName(CHAT_ID, "member-1", "Must not persist")

        assertEquals(GroupMemberMutationResult.FORBIDDEN, result)
        transaction {
            val chat = Chats.selectAll().where { Chats.id eq CHAT_ID }.single()
            assertEquals("Before", chat[Chats.groupName])
            assertEquals(10, chat[Chats.memberRevision])
            assertEquals(0L, GroupAuditLogs.selectAll().where { GroupAuditLogs.chatId eq CHAT_ID }.count())
        }
    }

    @Test
    fun `avatar mutation returns previous value and commits one revision`() {
        setupDb()

        val result = GroupProfileRepository().updateAvatar(
            chatId = CHAT_ID,
            actorId = "owner",
            avatarUrl = "/group-avatars/new.webp",
        )

        assertEquals(GroupMemberMutationResult.UPDATED, result.result)
        assertEquals("/group-avatars/old.webp", result.previousAvatarUrl)
        transaction {
            val chat = Chats.selectAll().where { Chats.id eq CHAT_ID }.single()
            assertEquals("/group-avatars/new.webp", chat[Chats.groupAvatar])
            assertEquals(11, chat[Chats.memberRevision])
            assertEquals(
                "AVATAR_UPDATED",
                GroupAuditLogs.selectAll().where { GroupAuditLogs.chatId eq CHAT_ID }
                    .single()[GroupAuditLogs.action],
            )
        }
    }

    @Test
    fun `bulk mute filters protected roles and bumps revision once`() {
        setupDb()
        val mutedUntil = System.currentTimeMillis() + 60_000

        val result = GroupModerationRepository().updateMembersMute(
            chatId = CHAT_ID,
            actorId = "admin",
            targetUserIds = listOf("owner", "admin", "member-1", "member-2", "member-2", "missing"),
            mutedUntil = mutedUntil,
        )

        assertEquals(GroupBulkMuteResult(GroupMemberMutationResult.UPDATED, 2), result)
        transaction {
            val participants = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq CHAT_ID }
                .associate { it[ChatParticipants.userId] to it[ChatParticipants.mutedUntil] }
            assertEquals(0, participants.getValue("owner"))
            assertEquals(0, participants.getValue("admin"))
            assertEquals(mutedUntil, participants.getValue("member-1"))
            assertEquals(mutedUntil, participants.getValue("member-2"))
            assertEquals(
                11,
                Chats.selectAll().where { Chats.id eq CHAT_ID }.single()[Chats.memberRevision],
            )
            val auditTargets = GroupAuditLogs.selectAll()
                .where { GroupAuditLogs.chatId eq CHAT_ID }
                .map { it[GroupAuditLogs.targetUserId] }
                .toSet()
            assertEquals(setOf("member-1", "member-2"), auditTargets)
        }
    }

    private fun insertParticipant(userId: String, role: String) {
        ChatParticipants.insert {
            it[chatId] = CHAT_ID
            it[ChatParticipants.userId] = userId
            it[ChatParticipants.role] = role
            it[joinedAt] = System.currentTimeMillis()
        }
    }

    private companion object {
        const val CHAT_ID = "group-1"
        val COUNTER = AtomicInteger()
    }
}
