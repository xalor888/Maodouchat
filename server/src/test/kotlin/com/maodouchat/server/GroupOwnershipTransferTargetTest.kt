package com.maodouchat.server

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ChatRepository
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
        val repo = ChatRepository()
        val group = repo.createChat(
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
            ChatRepository.TransferOwnershipResult.TARGET_DEACTIVATED,
            repo.transferOwnership(group.id, "u1", "u3")
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
        val repo = ChatRepository()
        val group = repo.createChat(
            participantIds = listOf("u1", "u2"),
            isGroup = true,
            groupName = "transfer ok",
            creatorId = "u1"
        )
        assertEquals(
            ChatRepository.TransferOwnershipResult.TRANSFERRED,
            repo.transferOwnership(group.id, "u1", "u2")
        )
        transaction {
            val roles = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq group.id }
                .associate { it[ChatParticipants.userId] to it[ChatParticipants.role] }
            assertEquals("OWNER", roles["u2"])
            assertEquals("ADMIN", roles["u1"])
        }
    }
}
