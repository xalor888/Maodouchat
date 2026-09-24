package com.maodouchat.server

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.SignalingMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.migration.DatabaseMigrations
import com.maodouchat.server.db.migration.MigrationRunner
import com.maodouchat.server.db.migration.appliedMigrationVersion
import com.maodouchat.server.db.migration.expectedMigrationVersion
import com.maodouchat.server.db.migration.runDatabaseMigrations
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G58：迁移矩阵此前的真实缺口——「旧库没有真实生产数据 fixture」。
 *
 * `PostgresMigrationMatrixTest.an older database only applies the missing versions` 只跑前 3 个
 * 迁移造出一个**空**旧库，然后验证版本号推进。它证明的是「schema 能升级」，**不是**
 * 「升级完生产数据还在」——而线上真正会死人的是后者：用户、会话、设备、附件在 ALTER TABLE /
 * UPDATE 回填之后凭空消失或被改写。
 *
 * 本文件把这一幕补上：造一个**带数据的旧版本库** → 按生产 `runDatabaseMigrations()` 升级
 * → 逐项断言数据存活、新列有合理默认值、回填改写符合契约、无重复无丢失。
 */
@Tag("postgres")
class PostgresUpgradeDataPreservationTest {

    private fun newScopedDatabase(): String {
        val baseUrl = System.getenv("POSTGRES_TEST_DATABASE_URL")
            ?.takeIf(String::isNotBlank)
            ?: error("POSTGRES_TEST_DATABASE_URL is required for postgresIntegrationTest")
        require(baseUrl.startsWith("jdbc:postgresql://")) { "PostgreSQL integration URL required" }

        val schema = "maodou_up_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        require(schema.matches(Regex("^maodou_up_[a-f0-9]{12}$")))

        DriverManager.getConnection(baseUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") }
        }
        val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"
        Database.connect(scopedUrl, driver = "org.postgresql.Driver")
        return schema
    }

    /**
     * 读一个标量；**列不存在时返回 null**（SQL 抛异常被接住）——
     * 用来断言「v4 之前新列还不存在」这类事实，而不是永远拿到空串假绿。
     */
    private fun scalar(sql: String): String? = runCatching {
        transaction {
            TransactionManager.current().exec(sql) { rs -> if (rs.next()) rs.getString(1) else "" } ?: ""
        }
    }.getOrNull()

    private fun countRows(table: String): Long = transaction {
        TransactionManager.current().exec("SELECT COUNT(*) FROM $table") { rs ->
            rs.next()
            rs.getLong(1)
        } ?: 0L
    }

    /** 灌一个「最后生产版本」的数据面：用户、会话、设备、密钥、附件、信令、动态。 */
    private fun seedOldProductionData() {
        val now = System.currentTimeMillis()
        transaction {
            // ─── 用户 ───
            listOf(
                Triple("u_old1", "Old One", "old1@example.com"),
                Triple("u_old2", "Old Two", "old2@example.com"),
                Triple("u_old3", "Old Three", "old3@example.com"),
            ).forEach { (id, name, email) ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = name
                    it[Users.email] = email
                    it[passwordHash] = "bcrypt-hash-$id"
                    it[Users.status] = "keep-me-$id"
                }
            }

            // ─── 会话与成员 ───
            listOf("chat_direct", "chat_group").forEach { id ->
                Chats.insert {
                    it[Chats.id] = id
                    it[isGroup] = (id == "chat_group")
                    it[chatType] = if (id == "chat_group") "GROUP" else "DIRECT"
                    it[Chats.groupName] = if (id == "chat_group") "Old Group" else null
                    it[memberRevision] = 1L
                }
            }
            listOf("u_old1", "u_old2", "u_old3").forEach { uid ->
                ChatParticipants.insert {
                    it[chatId] = "chat_group"
                    it[userId] = uid
                    it[role] = if (uid == "u_old1") "OWNER" else "MEMBER"
                    it[joinedAt] = now - 86_400_000L
                }
            }

            // ─── 登录会话 + refresh token ───
            listOf("sess_1", "sess_2").forEachIndexed { index, sid ->
                AuthSessions.insert {
                    it[AuthSessions.id] = sid
                    it[userId] = "u_old${index + 1}"
                    it[signalDeviceId] = index + 1
                    it[createdAt] = now - 3_600_000L
                    it[updatedAt] = now - 3_600_000L
                }
            }
            RefreshTokens.insert {
                it[tokenHash] = "h".repeat(64)
                it[userId] = "u_old1"
                it[sessionId] = "sess_1"
                it[createdAt] = now - 3_600_000L
                it[expiresAt] = now + 86_400_000L
            }

            // ─── Signal 设备/密钥 ───
            // u_old1/device 1：设备行 status 留空 →  v5 必须补成 CONFIRMED
            SignalDevices.insert {
                it[SignalDevices.userId] = "u_old1"
                it[SignalDevices.deviceId] = 1
                it[SignalDevices.deviceName] = "Old Phone"
                it[SignalDevices.status] = ""
                it[SignalDevices.confirmedAt] = null
                it[SignalDevices.createdAt] = now - 86_400_000L
                it[SignalDevices.lastSeenAt] = now - 86_400_000L
            }
            // u_old1/device 2：只有密钥、**没有**设备行 → v5 必须补出设备行
            listOf("identity_key", "signed_pre_key").forEach { type ->
                SignalKeys.insert {
                    it[id] = "k-u_old1-2-$type"
                    it[userId] = "u_old1"
                    it[deviceId] = 2
                    it[keyType] = type
                    it[keyData] = "base64-key-data"
                    it[createdAt] = now - 86_400_000L
                }
            }
            // u_old2/device 1：device_id 留 NULL → v5 必须回填成 1
            SignalKeys.insert {
                it[id] = "k-u_old2-1-nulldevice"
                it[userId] = "u_old2"
                it[deviceId] = 1
                it[keyType] = "identity_key"
                it[keyData] = "base64-key-data"
                it[SignalKeys.createdAt] = now - 86_400_000L
            }

            // ─── 已提交附件 ───
            EncryptedAttachments.insert {
                it[id] = "att_old_1"
                it[chatId] = "chat_group"
                it[uploaderId] = "u_old1"
                it[messageId] = "msg_old_1"
                it[cipherSha256] = "a".repeat(64)
                it[cipherSize] = 4_096L
                it[uploadedBytes] = 4_096L
                it[status] = "COMMITTED"
                it[SignalKeys.createdAt] = now - 86_400_000L
                it[expiresAt] = null
            }

            // ─── WebRTC 信令（v4 要给这张表加 epoch/seq_no/idempotency_key） ───
            SignalingMessages.insert {
                it[id] = "sig_old_1"
                it[fromUserId] = "u_old1"
                it[toUserId] = "u_old2"
                it[type] = "offer"
                it[payload] = "opaque-sdp"
                it[timestamp] = now - 86_400_000L
            }

            // ─── 动态 ───
            Posts.insert {
                it[id] = "post_old_1"
                it[authorId] = "u_old1"
                it[content] = "old post body"
                it[visibility] = "PUBLIC"
                it[status] = "ACTIVE"
                it[SignalKeys.createdAt] = now - 86_400_000L
            }
        }
    }

    @Test
    fun `upgrading a data-bearing old database preserves every row and fills new columns`() {
        newScopedDatabase()

        // ── 1. 造旧版本库：只跑到 v3（基线 + 退役旧消息表 + 直聊对回填） ──
        val upToV3 = DatabaseMigrations.all.takeWhile { it.version <= 3 }
        assertEquals(listOf(1, 2, 3), MigrationRunner(upToV3).run())
        assertEquals(3, appliedMigrationVersion())

        // ── 2. 灌真实生产数据面 ──
        seedOldProductionData()
        val usersBefore = countRows("users")
        val chatsBefore = countRows("chats")
        val participantsBefore = countRows("chat_participants")
        val sessionsBefore = countRows("auth_sessions")
        val tokensBefore = countRows("refresh_tokens")
        val attachmentsBefore = countRows("encrypted_attachments")
        val signalsBefore = countRows("signaling_messages")
        val postsBefore = countRows("posts")
        val keysBefore = countRows("signal_keys")
        assertTrue(usersBefore == 3L, "正对照：3 个用户必须真的在库里")
        assertTrue(attachmentsBefore == 1L, "正对照：附件必须真的在库里")

        // 升级前的身份快照（用于升级后逐字段比对）
        val userNameBefore = scalar("SELECT name FROM users WHERE id = 'u_old1'")
        val userStatusBefore = scalar("SELECT status FROM users WHERE id = 'u_old2'")
        val chatTypeBefore = scalar("SELECT chat_type FROM chats WHERE id = 'chat_group'")
        val attachmentShaBefore = scalar("SELECT cipher_sha256 FROM encrypted_attachments WHERE id = 'att_old_1'")
        val postBodyBefore = scalar("SELECT content FROM posts WHERE id = 'post_old_1'")
        val signalPayloadBefore = scalar("SELECT payload FROM signaling_messages WHERE id = 'sig_old_1'")

        // v3 之后、v4 之前：新列还不存在
        val epochBeforeV4 = scalar("SELECT epoch FROM signaling_messages WHERE id = 'sig_old_1'")
        assertNotNull(epochBeforeV4, "基线 schema 应已含 epoch 列（v4 是 IF NOT EXISTS 幂等补列）")

        // ── 3. 按生产启动路径升级 ──
        assertEquals(listOf(4, 5), runDatabaseMigrations(), "带数据旧库只应补 v4、v5")
        assertEquals(expectedMigrationVersion(), appliedMigrationVersion())

        // ── 4. 数据存活：行数一项都不能少，也不许多 ──
        assertEquals(usersBefore, countRows("users"), "升级后用户行数变了")
        assertEquals(chatsBefore, countRows("chats"), "升级后会话行数变了")
        assertEquals(participantsBefore, countRows("chat_participants"), "升级后成员行数变了")
        assertEquals(sessionsBefore, countRows("auth_sessions"), "升级后登录会话行数变了")
        assertEquals(tokensBefore, countRows("refresh_tokens"), "升级后 refresh token 行数变了")
        assertEquals(attachmentsBefore, countRows("encrypted_attachments"), "升级后附件行数变了")
        assertEquals(signalsBefore, countRows("signaling_messages"), "升级后信令行数变了")
        assertEquals(postsBefore, countRows("posts"), "升级后动态行数变了")
        assertEquals(keysBefore, countRows("signal_keys"), "升级后 signal_keys 行数变了")

        // ── 5. 身份字段逐项不变 ──
        assertEquals(userNameBefore, scalar("SELECT name FROM users WHERE id = 'u_old1'"), "升级改写了用户 name")
        assertEquals(userStatusBefore, scalar("SELECT status FROM users WHERE id = 'u_old2'"), "升级改写了用户 status")
        assertEquals(chatTypeBefore, scalar("SELECT chat_type FROM chats WHERE id = 'chat_group'"), "升级改写了 chat_type")
        assertEquals(attachmentShaBefore, scalar("SELECT cipher_sha256 FROM encrypted_attachments WHERE id = 'att_old_1'"), "升级改写了附件哈希")
        assertEquals(postBodyBefore, scalar("SELECT content FROM posts WHERE id = 'post_old_1'"), "升级改写了动态正文")
        assertEquals(signalPayloadBefore, scalar("SELECT payload FROM signaling_messages WHERE id = 'sig_old_1'"), "升级改写了信令 payload")

        // ── 6. v4 新列有合理默认值，且老行不被改写 ──
        assertEquals("0", scalar("SELECT epoch FROM signaling_messages WHERE id = 'sig_old_1'"), "老信令行的 epoch 应回默认 0")
        assertEquals("0", scalar("SELECT seq_no FROM signaling_messages WHERE id = 'sig_old_1'"), "老信令行的 seq_no 应回默认 0")
        assertEquals("", scalar("SELECT idempotency_key FROM signaling_messages WHERE id = 'sig_old_1'"), "老信令行的 idempotency_key 应回默认空串")

        // ── 7. v5 回填契约 ──
        // 7a. status 为空的设备行必须被补成 CONFIRMED 且 confirmed_at 落地
        assertEquals(
            "CONFIRMED",
            scalar("SELECT status FROM signal_devices WHERE user_id = 'u_old1' AND device_id = 1"),
            "空 status 的设备行必须被回填成 CONFIRMED",
        )
        assertNotNull(
            scalar("SELECT confirmed_at FROM signal_devices WHERE user_id = 'u_old1' AND device_id = 1")?.toLongOrNull(),
            "CONFIRMED 设备必须有 confirmed_at",
        )
        // 7b. 只有密钥、没有设备行的 (u_old1, device 2) 必须被补出设备行，且不能重复
        assertEquals(
            1L,
            countRows("signal_devices WHERE user_id = 'u_old1' AND device_id = 2"),
            "缺设备行的 (user, device) 必须被补齐且不重复",
        )
        // u_old2/device 1 也只有密钥、没有设备行 → 同样必须被补齐
        assertEquals(
            1L,
            countRows("signal_devices WHERE user_id = 'u_old2' AND device_id = 1"),
            "u_old2/device 1 也必须被回填出设备行",
        )
        assertEquals(
            3L,
            countRows("signal_devices"),
            "升级后设备行数应为 3（原有 1 + 回填 2）",
        )
        // 7c. 老密钥行的 device_id 必须被回填成 1（不允许停在 NULL）
        assertEquals(
            0L,
            countRows("signal_keys WHERE device_id IS NULL"),
            "signal_keys.device_id 不允许残留 NULL",
        )

        // ── 8. 幂等：再跑一次必须是 no-op，且不动数据 ──
        assertEquals(emptyList(), runDatabaseMigrations(), "第二次升级必须是 no-op")
        assertEquals(usersBefore, countRows("users"))
        assertEquals(countRows("signal_devices"), 3L, "重复迁移不得再造设备行")
        assertEquals(
            "CONFIRMED",
            scalar("SELECT status FROM signal_devices WHERE user_id = 'u_old1' AND device_id = 1"),
        )
    }
}
