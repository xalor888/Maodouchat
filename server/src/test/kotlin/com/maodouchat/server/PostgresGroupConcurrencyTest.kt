package com.maodouchat.server

import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupAuditRepository
import com.maodouchat.server.repository.GroupMembershipRepository
import com.maodouchat.server.repository.ConversationLifecycleRepository
import com.maodouchat.server.repository.ConversationCreationRepository
import com.maodouchat.server.repository.LeaveConversationResult
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
            val participants = ConversationParticipantRepository()
            val queries = ConversationQueryRepository()
            val audits = GroupAuditRepository()
            val creationRepo = ConversationCreationRepository()
            val membershipRepo = GroupMembershipRepository()

            val roleRaceGroup = creationRepo.create(
                participantIds = listOf("u1", "u2", "u3"),
                isGroup = true,
                groupName = "Role race",
                creatorId = "u1"
            )
            assertEquals(
                com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED,
                membershipRepo.updateRole(roleRaceGroup.id, "u1", "u2", "ADMIN")
            )
            val roleRace = listOf(
                async(Dispatchers.IO) {
                    membershipRepo.updateRole(roleRaceGroup.id, "u1", "u3", "ADMIN")
                },
                async(Dispatchers.IO) {
                    membershipRepo.removeMember(roleRaceGroup.id, "u2", "u3")
                }
            ).awaitAll()
            assertEquals(1, roleRace.count { it == com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED })
            participants.groupMembers(roleRaceGroup.id).firstOrNull { it.userId == "u3" }?.let { target ->
                assertEquals("ADMIN", target.role)
            }

            val ownershipGroup = creationRepo.create(
                participantIds = listOf("u1", "u2", "u3"),
                isGroup = true,
                groupName = "Ownership race",
                creatorId = "u1"
            )
            val transfer = async(Dispatchers.IO) {
                membershipRepo.transferOwnership(ownershipGroup.id, "u1", "u2")
            }
            val leave = async(Dispatchers.IO) { ConversationLifecycleRepository().leave(ownershipGroup.id, "u1") }
            assertEquals(com.maodouchat.server.repository.TransferOwnershipResult.TRANSFERRED, transfer.await())
            assertTrue(
                leave.await().result in setOf(
                    LeaveConversationResult.LEFT,
                    LeaveConversationResult.OWNER_TRANSFER_REQUIRED
                )
            )
            val ownershipMembers = participants.groupMembers(ownershipGroup.id)
            assertEquals(1, ownershipMembers.count { it.role == "OWNER" })
            assertEquals("OWNER", ownershipMembers.single { it.userId == "u2" }.role)
            assertEquals(
                1,
                audits.list(ownershipGroup.id, 100).count { it.action == "OWNERSHIP_TRANSFERRED" }
            )

            val addRaceGroup = creationRepo.create(
                participantIds = listOf("u1", "u2", "u3"),
                isGroup = true,
                groupName = "Add race",
                creatorId = "u1"
            )
            assertEquals(
                com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED,
                membershipRepo.updateRole(addRaceGroup.id, "u1", "u2", "ADMIN")
            )
            val addResults = listOf(
                async(Dispatchers.IO) { membershipRepo.addMembers(addRaceGroup.id, "u1", listOf("u4", "u5"), 500) },
                async(Dispatchers.IO) { membershipRepo.addMembers(addRaceGroup.id, "u2", listOf("u5", "u6"), 500) }
            ).awaitAll()
            assertTrue(addResults.all { it.result == com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED })
            val finalMembers = participants.groupMembers(addRaceGroup.id)
            assertEquals(finalMembers.map { it.userId }.toSet().size, finalMembers.size)
            assertTrue(setOf("u1", "u2", "u3", "u4", "u5", "u6").all { id -> finalMembers.any { it.userId == id } })
            assertEquals(3, audits.list(addRaceGroup.id, 100).count { it.action == "MEMBER_ADDED" })
            // memberRevision: create=1 + role update=1 + two addMembers commits=2 -> 4.
            assertEquals(4L, queries.getById(addRaceGroup.id)?.memberRevision)
        } finally {
            DriverManager.getConnection(baseUrl).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE") }
            }
        }
    }
}
