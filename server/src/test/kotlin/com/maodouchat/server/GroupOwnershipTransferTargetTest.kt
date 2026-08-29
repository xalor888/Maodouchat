package com.maodouchat.server

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.ConversationCreationRepository
import com.maodouchat.server.repository.GroupLifecycleService
import com.maodouchat.server.repository.GroupMembershipRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 9.241：转让群主不得指向已注销账号——注销清理与转让存在竞态窗口，
 * 转给幽灵账号会让群永久无可行使权力的群主（原群主已降为 ADMIN）。
 */
class GroupOwnershipTransferTargetTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:ownership-transfer-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

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
    fun `transfer to deactivated account is rejected and roles unchanged`() {
        setupDb()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1", "u2", "u3"),
            isGroup = true,
            groupName = "transfer target",
            creatorId = "u1"
        )
        // 模拟注销清理未完成窗口：u3 已标记注销但仍在群成员表
        transaction {
            Users.update({ Users.id eq "u3" }) { it[Users.deletedAt] = System.currentTimeMillis() }
        }
        assertEquals(
            com.maodouchat.server.repository.TransferOwnershipResult.TARGET_DEACTIVATED,
            GroupMembershipRepository().transferOwnership(group.id, "u1", "u3")
        )
        // 角色不变：u1 仍是 OWNER
        transaction {
            val role = ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq group.id) and (ChatParticipants.userId eq "u1") }
                .single()[ChatParticipants.role]
            assertEquals("OWNER", role)
        }
    }

    @Test
    fun `transfer to active member still succeeds`() {
        setupDb()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1", "u2"),
            isGroup = true,
            groupName = "transfer ok",
            creatorId = "u1"
        )
        assertEquals(
            com.maodouchat.server.repository.TransferOwnershipResult.TRANSFERRED,
            GroupMembershipRepository().transferOwnership(group.id, "u1", "u2")
        )
        transaction {
            val roles = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq group.id }
                .associate { it[ChatParticipants.userId] to it[ChatParticipants.role] }
            assertEquals("OWNER", roles["u2"])
            assertEquals("ADMIN", roles["u1"])
        }
    }

    @Test
    fun `group lifecycle service returns pre-mutation recipients and committed revision`() {
        setupDb()
        val queries = ConversationQueryRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1", "u2", "u3"),
            isGroup = true,
            groupName = "service boundary",
            creatorId = "u1"
        )
        val beforeRevision = queries.getById(group.id)!!.memberRevision
        val commit = GroupLifecycleService(GroupMembershipRepository()).removeMember(group.id, "u1", "u3")

        assertEquals(com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED, commit.result)
        assertEquals(setOf("u1", "u2", "u3"), commit.recipientsBefore.toSet())
        assertEquals(beforeRevision + 1, commit.memberRevisionAfter)
    }

    @Test
    fun `removing offline member deletes only their pending mailbox and bumps revision`() {
        setupDb()
        val queries = ConversationQueryRepository()
        val group = ConversationCreationRepository().create(
            participantIds = listOf("u1", "u2", "u3"),
            isGroup = true,
            groupName = "offline removal",
            creatorId = "u1",
        )
        transaction {
            MessagingV2Messages.insert {
                it[id] = "membership-envelope-message"
                it[conversationId] = group.id
                it[senderUserId] = "u1"
                it[senderDeviceId] = 1
                it[kind] = "DATA"
                it[recordClass] = "MESSAGE"
                it[groupRevision] = queries.getById(group.id)!!.memberRevision
                it[clientTimestamp] = 100L
                it[serverTimestamp] = 100L
                it[requestDigest] = "a".repeat(64)
            }
            listOf("u2", "u3").forEachIndexed { index, recipient ->
                MessagingV2Envelopes.insert {
                    it[id] = "membership-envelope-$recipient"
                    it[messageId] = "membership-envelope-message"
                    it[recipientUserId] = recipient
                    it[recipientDeviceId] = 1
                    it[ciphertextType] = "SIGNAL"
                    it[ciphertext] = "cipher-$recipient"
                    it[serverTimestamp] = 100L + index
                    it[acknowledgedAt] = null
                }
            }
        }

        val commit = GroupLifecycleService(GroupMembershipRepository())
            .removeMember(group.id, "u1", "u3")

        assertEquals(com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED, commit.result)
        assertEquals(2, commit.memberRevisionAfter)
        transaction {
            val pendingRecipients = MessagingV2Envelopes.selectAll()
                .where { MessagingV2Envelopes.messageId eq "membership-envelope-message" }
                .map { it[MessagingV2Envelopes.recipientUserId] }
            assertEquals(listOf("u2"), pendingRecipients)
            assertTrue(
                MessagingV2Messages.selectAll()
                    .where { MessagingV2Messages.id eq "membership-envelope-message" }
                    .any()
            )
        }
    }
}
