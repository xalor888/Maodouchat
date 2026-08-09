package com.maodouchat.server.plugins

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.MessageRepository
import com.maodouchat.server.repository.ReportRepository
import com.maodouchat.server.repository.SenderKeyDistributionRepository
import com.maodouchat.server.repository.SignalKeyRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 服务端数据保留期清理（8.x 调优轮次新增）综合测试。
 * 独立 JVM（forkEvery=1）跑单个 H2 内存库。
 */
class DataRetentionPurgeTest {

    private val dbUrl =
        "jdbc:h2:mem:retention-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"

    private fun setupDb() {
        // 直接以参数连接：绝不设置 DATABASE_URL 系统属性，避免泄漏污染同进程其它测试类。
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
    }

    @Test
    fun `all retention purges delete only stale rows`() {
        setupDb()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val old = now - 400 * day
        val recent = now - 10 * day

        // 预置数据：每个清理目标各插一条超期 + 一条近期
        transaction {
            listOf("u1", "u2").forEach { uid ->
                Users.insert {
                    it[id] = uid
                    it[Users.name] = uid
                    it[Users.email] = "$uid@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            // 好友请求：超期 PENDING + 近期 PENDING
            FriendRequests.insert {
                it[id] = "fr_old"
                it[fromUserId] = "u1"
                it[toUserId] = "u2"
                it[status] = "PENDING"
                it[createdAt] = old
            }
            FriendRequests.insert {
                it[id] = "fr_new"
                it[fromUserId] = "u1"
                it[toUserId] = "u2"
                it[status] = "PENDING"
                it[createdAt] = recent
            }
            // 群（Chats FK 依赖）
            Chats.insert {
                it[id] = "c1"
                it[chatType] = "GROUP"
            }
            ChatParticipants.insert {
                it[chatId] = "c1"
                it[userId] = "u1"
                it[joinedAt] = now
            }
            // 群玩法：超期 + 近期
            GroupCheckins.insert {
                it[chatId] = "c1"; it[userId] = "u1"; it[checkinDate] = "2024-01-01"
                it[streak] = 1; it[totalCount] = 1; it[checkedAt] = old
            }
            GroupCheckins.insert {
                it[chatId] = "c1"; it[userId] = "u1"; it[checkinDate] = "2026-07-01"
                it[streak] = 1; it[totalCount] = 1; it[checkedAt] = recent
            }
            GroupChains.insert {
                it[id] = "ch_old"; it[chatId] = "c1"; it[creatorId] = "u1"
                it[title] = "t"; it[topic] = "tp"; it[createdAt] = old
            }
            GroupChains.insert {
                it[id] = "ch_new"; it[chatId] = "c1"; it[creatorId] = "u1"
                it[title] = "t"; it[topic] = "tp"; it[createdAt] = recent
            }
            GroupChainEntries.insert {
                it[id] = "ce_old"; it[chainId] = "ch_old"; it[userId] = "u1"
                it[sequence] = 1; it[content] = "c"; it[createdAt] = old
            }
            GroupChainEntries.insert {
                it[id] = "ce_new"; it[chainId] = "ch_new"; it[userId] = "u1"
                it[sequence] = 1; it[content] = "c"; it[createdAt] = recent
            }
            GroupPkRounds.insert {
                it[id] = "pk_old"; it[chatId] = "c1"; it[creatorId] = "u1"
                it[leftTitle] = "L"; it[rightTitle] = "R"; it[createdAt] = old
            }
            GroupPkRounds.insert {
                it[id] = "pk_new"; it[chatId] = "c1"; it[creatorId] = "u1"
                it[leftTitle] = "L"; it[rightTitle] = "R"; it[createdAt] = recent
            }
            GroupPkVotes.insert {
                it[pkId] = "pk_old"; it[userId] = "u1"; it[choice] = "left"; it[votedAt] = old
            }
            GroupPkVotes.insert {
                it[pkId] = "pk_new"; it[userId] = "u1"; it[choice] = "left"; it[votedAt] = recent
            }
            // 群审计：超期 + 近期
            GroupAuditLogs.insert {
                it[id] = "ga_old"; it[chatId] = "c1"; it[actorId] = "u1"
                it[action] = "ADD_MEMBER"; it[createdAt] = old
            }
            GroupAuditLogs.insert {
                it[id] = "ga_new"; it[chatId] = "c1"; it[actorId] = "u1"
                it[action] = "ADD_MEMBER"; it[createdAt] = recent
            }
            // bot 命令日志：超期 + 近期
            BotCommandLogs.insert {
                it[id] = "bc_old"; it[botId] = "b1"; it[chatId] = "c1"; it[userId] = "u1"
                it[command] = "/ping"; it[createdAt] = old
            }
            BotCommandLogs.insert {
                it[id] = "bc_new"; it[botId] = "b1"; it[chatId] = "c1"; it[userId] = "u1"
                it[command] = "/ping"; it[createdAt] = recent
            }
            // Signal 已消费 prekey：超期 + 近期
            SignalKeys.insert {
                it[id] = "sk_old"; it[userId] = "u1"; it[keyType] = "consumed_pre_key"
                it[keyData] = "base64"; it[keyId] = 1; it[createdAt] = old
            }
            SignalKeys.insert {
                it[id] = "sk_new"; it[userId] = "u1"; it[keyType] = "consumed_pre_key"
                it[keyData] = "base64"; it[keyId] = 2; it[createdAt] = recent
            }
            // 消息 + 派生行：超期消息（用 400 天前的 timestamp）+ 近期消息
            Messages.insert {
                it[id] = "m_old"; it[chatId] = "c1"; it[senderId] = "u1"
                it[content] = "x"; it[type] = "TEXT"; it[timestamp] = old
            }
            Messages.insert {
                it[id] = "m_new"; it[chatId] = "c1"; it[senderId] = "u1"
                it[content] = "x"; it[type] = "TEXT"; it[timestamp] = recent
            }
            MessageReactions.insert {
                it[messageId] = "m_old"; it[userId] = "u2"; it[emoji] = "👍"; it[reactedAt] = recent
            }
            MessageReactions.insert {
                it[messageId] = "m_new"; it[userId] = "u2"; it[emoji] = "👍"; it[reactedAt] = recent
            }
            ReadReceipts.insert {
                it[messageId] = "m_old"; it[userId] = "u2"; it[readAt] = recent
            }
            ReadReceipts.insert {
                it[messageId] = "m_new"; it[userId] = "u2"; it[readAt] = recent
            }
            StarMessages.insert {
                it[messageId] = "m_old"; it[userId] = "u2"; it[starredAt] = recent
            }
            StarMessages.insert {
                it[messageId] = "m_new"; it[userId] = "u2"; it[starredAt] = recent
            }
        }

        // 执行全部清理（保留期参数故意缩短验证只删超期）
        val friendDeleted = FriendRepository().expireStalePending(days = 30)
        val playDeleted = GroupCheckinRepository.purgeOldData(retentionDays = 365)
        val groupAuditDeleted = ChatRepository().purgeOldAuditLogs(retentionDays = 365)
        val botDeleted = BotRepository.purgeOldCommandLogs(retentionDays = 180)
        val preKeyDeleted = SignalKeyRepository().purgeConsumedPreKeys(retentionDays = 30)
        val derivedDeleted = MessageRepository().purgeOldDerivedRows(retentionDays = 365)

        transaction {
            assertEquals(1, friendDeleted, "仅删超期 PENDING 好友请求")
            assertEquals(1, playDeleted["checkins"])
            assertEquals(1, playDeleted["chains"])
            assertEquals(1, playDeleted["chainEntries"])
            assertEquals(1, playDeleted["pkRounds"])
            assertEquals(1, playDeleted["pkVotes"])
            assertEquals(1, groupAuditDeleted, "仅删超期群审计")
            assertEquals(1, botDeleted, "仅删超期 bot 命令日志")
            assertEquals(1, preKeyDeleted, "仅删超期 consumed prekey")
            assertEquals(2, derivedDeleted, "超期消息的 reaction+收藏共 2 行（ReadReceipts 保留防未读振荡）")
            // 近期数据全部保留
            assertTrue(FriendRequests.selectAll().count() == 1L)
            assertTrue(GroupCheckins.selectAll().count() == 1L)
            assertTrue(GroupChains.selectAll().count() == 1L)
            assertTrue(GroupChainEntries.selectAll().count() == 1L)
            assertTrue(GroupPkRounds.selectAll().count() == 1L)
            assertTrue(GroupPkVotes.selectAll().count() == 1L)
            assertTrue(GroupAuditLogs.selectAll().count() == 1L)
            assertTrue(BotCommandLogs.selectAll().count() == 1L)
            assertTrue(SignalKeys.selectAll().count() == 1L)
            assertTrue(MessageReactions.selectAll().count() == 1L)
            // ReadReceipts 不参与清理（未读数按回执存在与否计算，清掉会让旧消息周期性"复活"为未读）
            assertTrue(ReadReceipts.selectAll().count() == 2L)
            assertTrue(StarMessages.selectAll().count() == 1L)
        }
    }
}
