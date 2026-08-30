package com.maodouchat.server.repository

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

/**
 * B05 子项 3：每个成员事务恰好递增一次 memberRevision，并产生领域审计。
 *
 * H2 内存库覆盖 addMembers / updateRole / removeMember / transferOwnership 四条主路径
 * 的 revision 与 audit 不变量（邀请 accept / 链接加入已在 GroupInviteMembersTest 覆盖）。
 */
class GroupMembershipRevisionAuditTest {

    private var database: Database? = null

    private val dbUrl =
        "jdbc:h2:mem:membership-revision-audit-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

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
    fun `each membership mutation increments revision exactly once and writes audit`() {
        setupDb()
        val membership = GroupMembershipRepository()
        val creation = ConversationCreationRepository()

        val group = creation.create(
            participantIds = listOf("u1"),
            isGroup = true,
            groupName = "invariants",
            creatorId = "u1",
        )
        // create sets memberRevision = 1
        assertRevision(group.id, 1L)

        // addMembers: +1 revision, one MEMBER_ADDED audit
        assertEquals(
            GroupMemberMutationResult.UPDATED,
            membership.addMembers(group.id, "u1", listOf("u2"), maxMembers = 100).result,
        )
        assertRevision(group.id, 2L)
        assertAuditCount(group.id, "MEMBER_ADDED", "u2", 1)

        // updateRole: +1 revision, one MEMBER_PROMOTED audit
        assertEquals(
            GroupMemberMutationResult.UPDATED,
            membership.updateRole(group.id, "u1", "u2", "ADMIN"),
        )
        assertRevision(group.id, 3L)
        assertAuditCount(group.id, "MEMBER_PROMOTED", "u2", 1)

        // removeMember: +1 revision, one MEMBER_REMOVED audit
        assertEquals(
            GroupMemberMutationResult.UPDATED,
            membership.removeMember(group.id, "u1", "u2"),
        )
        assertRevision(group.id, 4L)
        assertAuditCount(group.id, "MEMBER_REMOVED", "u2", 1)

        // add u3 then transfer ownership: each +1 revision
        assertEquals(
            GroupMemberMutationResult.UPDATED,
            membership.addMembers(group.id, "u1", listOf("u3"), maxMembers = 100).result,
        )
        assertRevision(group.id, 5L)
        assertEquals(
            TransferOwnershipResult.TRANSFERRED,
            membership.transferOwnership(group.id, "u1", "u3"),
        )
        assertRevision(group.id, 6L)
        assertAuditCount(group.id, "OWNERSHIP_TRANSFERRED", "u3", 1)
    }

    private fun assertRevision(chatId: String, expected: Long) {
        transaction {
            val revision = Chats.selectAll().where { Chats.id eq chatId }.single()[Chats.memberRevision]
            assertEquals(expected, revision, "memberRevision mismatch for $chatId")
        }
    }

    private fun assertAuditCount(chatId: String, action: String, targetUserId: String?, expected: Int) {
        transaction {
            val count = GroupAuditLogs.selectAll().where {
                (GroupAuditLogs.chatId eq chatId) and
                    (GroupAuditLogs.action eq action) and
                    (GroupAuditLogs.targetUserId eq targetUserId)
            }.count()
            assertEquals(expected.toLong(), count, "audit count mismatch for $action / $targetUserId")
        }
    }
}
