package com.maodouchat.server.common

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ConversationCreationRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G188 补集：`isValidGroupSignalMetadata` 的**查库后三段**（chat 不存在 / 非群 / 成员不属该群）
 * 以及两条合法 mesh 边界（2 人与 6 人）。
 *
 * `CallSignalingValidatorsTest` 只钉了查库前守卫，因为 `ConversationQueryRepository` 是
 * final class 且本模块无 mock 依赖；这里按 `PerUserStateIsolationRouteTest` 的 repo 层夹具
 * 直接 `Database.connect` + `initDatabase()` 起内存 H2，走真实查询。
 *
 * 断言刻意成对写（真群通过 + 掺一个外部成员拒绝）：若 `getById` 因建群失败而恒 null，
 * 只有合法用例失败，不会让"该拒的"用例假绿。
 */
class CallSignalingGroupMetadataDbTest {

    private val repo = ConversationQueryRepository()

    private fun db() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:callmeta-${Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        UserRepository().createDefaultUsers()
    }

    private fun groupMeta(groupId: String, members: List<String>, from: String = "u1", to: String = "u2") =
        CallSignalingValidators.isValidGroupSignalMetadata(
            groupId = groupId,
            groupMemberIds = members,
            groupInvite = false,
            callId = "call-1",
            fromUserId = from,
            toUserId = to,
            conversationQueryRepository = repo,
        )

    @Test
    fun `unknown groupId is rejected after the db lookup`() {
        db()
        assertFalse(groupMeta("c_does-not-exist", listOf("u1", "u2")), "chat 不存在必须拒")
    }

    @Test
    fun `direct chat used as groupId is rejected even though both endpoints are in it`() {
        db()
        val dm = ConversationCreationRepository().create(listOf("u1", "u2"), isGroup = false)
        assertFalse(groupMeta(dm.id, listOf("u1", "u2")), "1:1 私聊不允许走群 mesh 信令")
    }

    @Test
    fun `a 2-member and a 6-member mesh pass when all declared members really belong to the group`() {
        db()
        val pair = ConversationCreationRepository().create(listOf("u1", "u2"), isGroup = true, groupName = "mesh2", creatorId = "u1")
        assertTrue(groupMeta(pair.id, listOf("u1", "u2")), "2 人是合法 mesh 下界（守卫已过，查库也应过）")

        val six = listOf("u1", "u2", "u3", "u4", "u5", "u6")
        val sixChat = ConversationCreationRepository().create(six, isGroup = true, groupName = "mesh6", creatorId = "u1")
        assertTrue(groupMeta(sixChat.id, six, from = "u1", to = "u6"), "6 人是合法 mesh 上界")
    }

    @Test
    fun `declared member outside the real group poisons the whole mesh`() {
        db()
        val chat = ConversationCreationRepository().create(listOf("u1", "u2", "u3"), isGroup = true, groupName = "mesh3", creatorId = "u1")
        // 掺一个真实存在但不在群里的 u4：哪怕端点 u1/u2 都在群，也必须整体拒
        assertFalse(groupMeta(chat.id, listOf("u1", "u2", "u4")))
        // 对照：去掉 u4 后同一群应通过（排除「恒 false」假绿）
        assertTrue(groupMeta(chat.id, listOf("u1", "u2", "u3")))
    }
}
