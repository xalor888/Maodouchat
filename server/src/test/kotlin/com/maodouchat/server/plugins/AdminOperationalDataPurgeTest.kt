package com.maodouchat.server.plugins

import com.maodouchat.server.db.AnnouncementAcks
import com.maodouchat.server.db.AuditExportRecords
import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * purgeAdminOperationalData 保留期清理测试。
 * 独立 JVM（build.gradle.kts forkEvery=1）跑单个 H2 内存库，不干扰其他测试类。
 */
class AdminOperationalDataPurgeTest {

    @Test
    fun `purge deletes only rows older than retention window and is a no-op when empty`() {
        val dbUrl =
            "jdbc:h2:mem:purge-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        // 直接以参数连接：绝不设置 DATABASE_URL 系统属性，避免泄漏污染同进程其它测试类。
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        // 空库调用应是 no-op
        val emptyRun = purgeAdminOperationalData()
        assertTrue(emptyRun.values.all { it == 0 }, "空库清理应为 no-op")

        val now = System.currentTimeMillis()
        val day = 86_400_000L
        // 400 天前（超 365 天保留期）
        val old = now - 400 * day
        // 10 天前（应保留）
        val recent = now - 10 * day

        transaction {
            Users.insert {
                it[id] = "u_purge"
                it[Users.name] = "Purge"
                it[Users.email] = "purge@test.local"
                it[Users.passwordHash] = "x"
            }
            Users.insert {
                it[id] = "u_purge2"
                it[Users.name] = "Purge2"
                it[Users.email] = "purge2@test.local"
                it[Users.passwordHash] = "x"
            }
            SystemAnnouncements.insert {
                it[id] = "ann_old"
                it[title] = "old"
                it[content] = "c"
                it[startsAt] = now
                it[expiresAt] = now + day
                it[createdAt] = now
                it[updatedAt] = now
            }
            // 公告 ack：一条超 90 天，一条近期
            AnnouncementAcks.insert {
                it[announcementId] = "ann_old"
                it[userId] = "u_purge"
                it[ackedAt] = old
            }
            AnnouncementAcks.insert {
                it[announcementId] = "ann_old"
                it[userId] = "u_purge2"
                it[ackedAt] = recent
            }
            DeviceEventConsistencyLog.insert {
                it[id] = "dev_old"
                it[userId] = "u_purge"
                it[deviceId] = 1
                it[eventType] = "WS_SYNC"
                it[seq] = 1
                it[status] = "STALE"
                it[firstSeenAt] = old
                it[lastSeenAt] = old
            }
            DeviceEventConsistencyLog.insert {
                it[id] = "dev_new"
                it[userId] = "u_purge"
                it[deviceId] = 1
                it[eventType] = "WS_SYNC"
                it[seq] = 2
                it[status] = "STALE"
                it[firstSeenAt] = recent
                it[lastSeenAt] = recent
            }
            AuditExportRecords.insert {
                it[id] = "exp_old"
                it[actorId] = "u_purge"
                it[scope] = "ADMIN_AUDIT"
                it[fromMs] = old
                it[toMs] = old + 1
                it[requestedAt] = old
            }
            AuditExportRecords.insert {
                it[id] = "exp_new"
                it[actorId] = "u_purge"
                it[scope] = "ADMIN_AUDIT"
                it[fromMs] = recent
                it[toMs] = recent + 1
                it[requestedAt] = recent
            }
            ModerationAuditLog.insert {
                it[ModerationAuditLog.id] = "mod_old"
                it[actorId] = "u_purge"
                it[action] = "REPORT_STATUS_UPDATE"
                it[createdAt] = old
            }
            ModerationAuditLog.insert {
                it[ModerationAuditLog.id] = "mod_new"
                it[actorId] = "u_purge"
                it[action] = "REPORT_STATUS_UPDATE"
                it[createdAt] = recent
            }
        }

        val deleted = purgeAdminOperationalData()

        transaction {
            assertEquals(1, deleted["announcementAcks"], "仅删超 90 天的 ack")
            assertEquals(1, deleted["deviceEventLogs"], "仅删超 30 天的异常日志")
            assertEquals(1, deleted["auditExportRecords"], "仅删超 180 天的导出记录")
            assertEquals(1, deleted["moderationAuditLogs"], "仅删超 365 天的审计")
            assertTrue(AnnouncementAcks.selectAll().count() == 1L, "近期 ack 保留")
            assertTrue(DeviceEventConsistencyLog.selectAll().count() == 1L, "近期异常日志保留")
            assertTrue(AuditExportRecords.selectAll().count() == 1L, "近期导出记录保留")
            assertTrue(ModerationAuditLog.selectAll().count() == 1L, "近期审计保留")
        }
    }
}
