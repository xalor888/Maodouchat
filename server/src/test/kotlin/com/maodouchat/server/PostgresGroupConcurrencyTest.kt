package com.maodouchat.server

import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("postgres")
class PostgresGroupConcurrencyTest {
    @Test
    fun `group mutations are linearizable on PostgreSQL row locks`() = runBlocking {
        val baseUrl = System.getenv("POSTGRES_TEST_DATABASE_URL")
            ?.takeIf(String::isNotBlank)
            ?: error("POSTGRES_TEST_DATABASE_URL is required for postgresIntegrationTest")
        require(baseUrl.startsWith("jdbc:postgresql://")) { "PostgreSQL integration URL required" }
        val schema = "maodou_it_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        require(schema.matches(Regex("^maodou_it_[a-f0-9]{12}$")))

        DriverManager.getConnection(baseUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") }
        }
        val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"

        try {
            Database.connect(scopedUrl, driver = "org.postgresql.Driver")
            initDatabase()
            UserRepository().createDefaultUsers()
            val repo = ChatRepository()

            val roleRaceGroup = repo.createChat(
                participantIds = listOf("u1", "u2", "u3"),
                isGroup = true,
                groupName = "Role race",
                creatorId = "u1"
            )
            assertEquals(
                ChatRepository.GroupMemberMutationResult.UPDATED,
                repo.updateGroupMemberRoleAsOwner(roleRaceGroup.id, "u1", "u2", "ADMIN")
            )
            val roleRace = listOf(
                async(Dispatchers.IO) {
                    repo.updateGroupMemberRoleAsOwner(roleRaceGroup.id, "u1", "u3", "ADMIN")
                },
                async(Dispatchers.IO) {
                    repo.removeGroupMemberAs(roleRaceGroup.id, "u2", "u3")
                }
            ).awaitAll()
            assertEquals(1, roleRace.count { it == ChatRepository.GroupMemberMutationResult.UPDATED })
            repo.getGroupMembers(roleRaceGroup.id).firstOrNull { it.userId == "u3" }?.let { target ->
                assertEquals("ADMIN", target.role)
            }

            val ownershipGroup = repo.createChat(
                participantIds = listOf("u1", "u2", "u3"),
                isGroup = true,
                groupName = "Ownership race",
                creatorId = "u1"
            )
            val transfer = async(Dispatchers.IO) { repo.transferOwnership(ownershipGroup.id, "u1", "u2") }
            val leave = async(Dispatchers.IO) { repo.leaveChat(ownershipGroup.id, "u1") }
            assertEquals(ChatRepository.TransferOwnershipResult.TRANSFERRED, transfer.await())
            assertTrue(
                leave.await().result in setOf(
                    ChatRepository.LeaveChatResult.LEFT,
                    ChatRepository.LeaveChatResult.OWNER_TRANSFER_REQUIRED
                )
            )
            val ownershipMembers = repo.getGroupMembers(ownershipGroup.id)
            assertEquals(1, ownershipMembers.count { it.role == "OWNER" })
            assertEquals("OWNER", ownershipMembers.single { it.userId == "u2" }.role)
            assertEquals(
                1,
                repo.getGroupAudit(ownershipGroup.id, 100).count { it.action == "OWNERSHIP_TRANSFERRED" }
            )

            val addRaceGroup = repo.createChat(
                participantIds = listOf("u1", "u2", "u3"),
                isGroup = true,
                groupName = "Add race",
                creatorId = "u1"
            )
            assertEquals(
                ChatRepository.GroupMemberMutationResult.UPDATED,
                repo.updateGroupMemberRoleAsOwner(addRaceGroup.id, "u1", "u2", "ADMIN")
            )
            val addResults = listOf(
                async(Dispatchers.IO) { repo.addGroupMembersAs(addRaceGroup.id, "u1", listOf("u4", "u5"), 500) },
                async(Dispatchers.IO) { repo.addGroupMembersAs(addRaceGroup.id, "u2", listOf("u5", "u6"), 500) }
            ).awaitAll()
            assertTrue(addResults.all { it.result == ChatRepository.GroupMemberMutationResult.UPDATED })
            val finalMembers = repo.getGroupMembers(addRaceGroup.id)
            assertEquals(finalMembers.map { it.userId }.toSet().size, finalMembers.size)
            assertTrue(setOf("u1", "u2", "u3", "u4", "u5", "u6").all { id -> finalMembers.any { it.userId == id } })
            assertEquals(3, repo.getGroupAudit(addRaceGroup.id, 100).count { it.action == "MEMBER_ADDED" })
            // memberRevision：createChat=1 + 上面 updateGroupMemberRoleAsOwner 角色变更=1 + 两次 addGroupMembersAs=2 → 4
            assertEquals(4L, repo.getChatById(addRaceGroup.id)?.memberRevision)
        } finally {
            DriverManager.getConnection(baseUrl).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE") }
            }
        }
    }
}
