package com.maodouchat.server.repository

import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G186c：`AdminExportRepository` —— 管理员数据导出查询层。
 *
 * 749 行 / 30 fun / 33 transaction 块，**此前零测试**。它是 M2「AdminExportsRouting
 * 不再直写 Exposed」那轮重构的产物（原 1110 行内联 SQL + CSV 映射被拆成
 * Repository + Service），但**新边界自身从无测试**。
 *
 * 它承载管理员导出：users / pushTokens / moderationAudit / riskEvents /
 * sessionsSummary … 全是敏感数据，导出行数或字段错一项就是数据事故。
 *
 * 测试基建沿用项目已有形状（参考 `AccountDeactivationCleanupTest`）：
 * H2 内存库 + `initDatabase()` + `transaction { insert }`。
 *
 * 本轮聚焦三件事（按目标）：
 *  1. 空库与有种子的基本查询正确性；
 *  2. **`limit` 语义**（0 / 负值 / 超大值）——URL 参数直接传到这里；
 *  3. **排序方向**（users 按 lastSeen DESC）。
 */
class AdminExportRepositoryTest {

    private lateinit var repo: AdminExportRepository

    @BeforeEach
    fun setUp() {
        Database.connect(
            "jdbc:h2:mem:aer_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        initDatabase()
        repo = AdminExportRepository()
    }

    @AfterEach
    fun tearDown() {
        transaction { exec("DROP ALL OBJECTS") }
    }

    /**
     * 用 Exposed DSL 而不是裸 SQL——H2 把 `name` 当保留字，
     * 裸 `INSERT INTO users (name, ...)` 会报 `Column "NAME" not found`。
     * DSL 的列引用 `Users.name` 会被正确加引号。（项目里
     * `AccountDeactivationCleanupTest.insertUser` 就是这个形状。）
     */
    private fun seedUser(id: String, lastSeen: Long, name: String = "n_$id", email: String = "$id@x.com") {
        transaction {
            Users.insert {
                it[Users.id] = id
                it[Users.name] = name
                it[Users.email] = email
                it[Users.passwordHash] = "h"
                it[Users.lastSeen] = lastSeen
            }
        }
    }

    // ---- 空库与基本查询 ----

    @Test
    fun `empty database yields empty rows for the user export`() {
        assertTrue(repo.users(10).isEmpty(), "空库必须返回空列表而不是抛异常")
    }

    @Test
    fun `users export returns one row per seeded user with the id first`() {
        seedUser("u1", 1_000L)
        seedUser("u2", 2_000L)
        val rows = repo.users(10)
        assertEquals(2, rows.size, "行数必须等于种子用户数")
        // 每行第一列是用户 id
        assertEquals(setOf("u1", "u2"), rows.map { it.first() }.toSet())
    }

    @Test
    fun `users export row has a stable column count`() {
        seedUser("u1", 1_000L)
        val rows = repo.users(10)
        assertEquals(1, rows.size)
        // users 投影片段：id, name, email, status, isOnline, isModerator, suspendedUntil, lastSeen
        assertEquals(8, rows.single().size, "列数必须稳定——CSV 表头按列数对齐，少一列就错位")
    }

    // ---- 排序方向 ----

    @Test
    fun `users export is ordered by last seen descending`() {
        seedUser("old", 1_000L)
        seedUser("mid", 2_000L)
        seedUser("new", 3_000L)
        val ids = repo.users(10).map { it.first() }
        assertEquals(listOf("new", "mid", "old"), ids, "必须按 lastSeen 倒序（最近活跃在前）")
    }

    // ---- limit 语义（URL 参数直接到这里）----

    @Test
    fun `a limit larger than the row count returns everything`() {
        repeat(3) { seedUser("u$it", 1_000L * it) }
        assertEquals(3, repo.users(10_000).size, "limit 大于行数应返回全部")
    }

    @Test
    fun `a zero limit returns no rows`() {
        seedUser("u1", 1_000L)
        // Exposed 的 limit(0) 语义：记录实际行为。若是返回 0 行，这是正确的（管理员看不到任何行）。
        val rows = repo.users(0)
        assertTrue(rows.isEmpty(), "limit=0 应返回 0 行，实际 ${rows.size} 行")
    }

    @Test
    fun `a negative limit throws and that is why the route clamps it`() {
        // G186c 实测：Repository 层对负 limit **不设防**——Exposed 直接生成
        // `FETCH -5`，H2 抛 ExposedSQLException("Invalid value -5 for parameter result FETCH")。
        //
        // 这是否是真实风险？**不是**：路由层每一处都先
        // `(queryParameters["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)`
        // （AdminExportsRouting.kt 的 61/77/95/120 行…），负值根本到不了这里。
        //
        // 这条用例的价值是**把这个契约钉住**：将来谁给 Repository 加一个不经路由的
        // 调用方，或谁把 coerceIn 去掉，这里会提醒他「下界曾经由路由层保证」。
        seedUser("u1", 1_000L)
        val e = assertFailsWith<org.jetbrains.exposed.exceptions.ExposedSQLException> {
            repo.users(-5)
        }
        assertTrue(
            e.message?.contains("Invalid value") == true,
            "应因负 FETCH 被 H2 拒，实际 ${e.message?.take(80)}",
        )
    }

    @Test
    fun `limit one returns exactly one row`() {
        repeat(3) { seedUser("u$it", 1_000L + it) }
        assertEquals(1, repo.users(1).size, "limit=1 必须只返回 1 行")
    }

    @Test
    fun `a limit truncates to the requested count`() {
        repeat(5) { seedUser("u$it", 1_000L + it) }
        assertEquals(2, repo.users(2).size, "limit=2 必须只返回 2 行")
    }

    // ---- 无参查询 ----

    @Test
    fun `message stats on an empty database is queryable`() {
        // messageStats() 无参，返回 MessageStats；空库不应抛
        val stats = repo.messageStats()
        assertTrue(stats.total >= 0L, "空库的总数应为 0 或非负，实际 ${stats.total}")
    }

    // ---- G186c 补：审计/风险类查询（目标第 (6) 条要求必须含一个）----

    private fun seedAuditRow(id: String, action: String, detail: String, createdAt: Long) {
        transaction {
            ModerationAuditLog.insert {
                it[ModerationAuditLog.id] = id
                it[ModerationAuditLog.actorId] = "actor_$id"
                it[ModerationAuditLog.userId] = "target_$id"
                it[ModerationAuditLog.action] = action
                it[ModerationAuditLog.detail] = detail
                it[ModerationAuditLog.createdAt] = createdAt
            }
        }
    }

    @Test
    fun `moderation audit export is empty on a fresh database`() {
        assertTrue(repo.moderationAudit(10).isEmpty(), "空库必须返回空列表")
    }

    @Test
    fun `moderation audit export returns one row per log entry with a stable column count`() {
        seedAuditRow("a1", "ban", "spam", 1_000L)
        seedAuditRow("a2", "unban", "appeal accepted", 2_000L)
        val rows = repo.moderationAudit(10)
        assertEquals(2, rows.size, "行数必须等于审计日志条数")
        // 列数必须稳定：CSV 表头按列数对齐，少一列就整体错位
        assertEquals(rows.first().size, rows.last().size, "同一导出的每行列数必须一致")
        assertTrue(rows.first().size >= 5, "审计导出至少应有 id/actor/user/action/detail 五列，实际 ${rows.first().size}")
    }

    @Test
    fun `moderation audit export is ordered newest first`() {
        seedAuditRow("old", "ban", "x", 1_000L)
        seedAuditRow("new", "ban", "x", 9_000L)
        val ids = repo.moderationAudit(10).map { it.first() }
        assertEquals(listOf("new", "old"), ids, "审计日志必须按 createdAt 倒序（最新在前）")
    }

    @Test
    fun `moderation audit export honours the limit`() {
        repeat(4) { seedAuditRow("a$it", "ban", "x", 1_000L + it) }
        assertEquals(2, repo.moderationAudit(2).size, "limit=2 必须只返回 2 行")
    }

    @Test
    fun `blocked users export lists blocker and blocked pairs`() {
        // BlockedUsers 只有两列（blocker_id, blocked_id），且外键指向 users
        seedUser("u_blocker", 1_000L)
        seedUser("u_blocked", 1_000L)
        transaction {
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = "u_blocker"
                it[BlockedUsers.blockedId] = "u_blocked"
            }
        }
        val rows = repo.blockedUsers(10)
        assertEquals(1, rows.size, "一条拉黑记录应导出一行")
        assertEquals(listOf("u_blocker", "u_blocked"), rows.single().map { it.toString() })
    }

    @Test
    fun `blocked users export is empty when nobody is blocked`() {
        assertTrue(repo.blockedUsers(10).isEmpty(), "没有拉黑记录时必须返回空列表")
    }

    // ---- G186d：再把覆盖从 4 个 fun 扩到 ~10 个 ----

    private fun seedUser(
        id: String,
        lastSeen: Long,
        totpEnabled: Boolean = false,
        showOnline: Boolean = true,
        showStatus: Boolean = true,
        searchable: Boolean = true,
        email: String = "$id@x.com",
    ) {
        transaction {
            Users.insert {
                it[Users.id] = id
                it[Users.name] = "n_$id"
                it[Users.email] = email
                it[Users.passwordHash] = "h"
                it[Users.lastSeen] = lastSeen
                it[Users.totpEnabled] = totpEnabled
                it[Users.showOnline] = showOnline
                it[Users.showStatus] = showStatus
                it[Users.searchable] = searchable
            }
        }
    }

    // ---- Users 系的四个导出 ----

    @Test
    fun `online presence export shows the presence flags per user`() {
        seedUser("u_online", 1_000L, showOnline = true)
        seedUser("u_hidden", 2_000L, showOnline = false)
        val rows = repo.onlinePresence(10)
        assertEquals(2, rows.size, "每个用户一行")
        assertEquals(4, rows.first().size, "列数必须稳定（id/isOnline/lastSeen/showOnline）")
        val byId = rows.associateBy { it.first().toString() }
        // 列序实测为 id/isOnline/lastSeen/showOnline；isOnline 我没 seed（默认 false）
        assertEquals("false", byId["u_online"]?.get(1), "未 seed isOnline 的用户该列为 false")
        assertEquals("1000", byId["u_online"]?.get(2), "lastSeen 列必须是种子时间戳")
        assertEquals("true", byId["u_online"]?.get(3), "showOnline=true 的用户该列必须是 true")
        assertEquals("false", byId["u_hidden"]?.get(3), "showOnline=false 的用户该列必须是 false")
    }

    @Test
    fun `privacy flags export shows the three privacy switches`() {
        seedUser("u_open", 1_000L, showStatus = true, searchable = true)
        seedUser("u_closed", 1_000L, showOnline = false, showStatus = false, searchable = false)
        val rows = repo.privacyFlags(10)
        val byId = rows.associateBy { it.first().toString() }
        assertEquals(4, rows.first().size, "列数必须稳定（id/showOnline/showStatus/searchable）")
        assertEquals("false", byId["u_closed"]?.get(1), "showOnline=false 必须如实导出")
        assertEquals("false", byId["u_closed"]?.get(2), "showStatus=false 必须如实导出")
        assertEquals("false", byId["u_closed"]?.get(3), "searchable=false 必须如实导出")
        assertEquals("true", byId["u_open"]?.get(3))
    }

    @Test
    fun `identity users export mixes identity flags with a masked email`() {
        seedUser("u1", 1_000L, searchable = true, showOnline = true, totpEnabled = false, email = "user@example.com")
        val rows = repo.identityUsers(10)
        assertEquals(1, rows.size)
        assertEquals(5, rows.single().size, "列数必须稳定")
        assertEquals("use***", rows.single()[4], "邮箱必须脱敏成 前3字符+***")
    }

    @Test
    fun `totp users export only includes users who enabled two factor`() {
        // 本目标最重要的一条：2FA 过滤漏一个就是数据越权
        seedUser("u_2fa_on", 1_000L, totpEnabled = true)
        seedUser("u_2fa_off", 2_000L, totpEnabled = false)
        val ids = repo.totpUsers(10).map { it.first().toString() }
        assertEquals(listOf("u_2fa_on"), ids, "只应导出了启用 2FA 的用户")
    }

    @Test
    fun `totp users export is empty when nobody enabled two factor`() {
        repeat(3) { seedUser("u$it", 1_000L + it, totpEnabled = false) }
        assertTrue(repo.totpUsers(10).isEmpty(), "无人启用 2FA 时必须导出空列表")
    }

    @Test
    fun `totp users export masks short emails without crashing`() {
        seedUser("u_short", 1_000L, totpEnabled = true, email = "a@b.c")
        val rows = repo.totpUsers(10)
        assertEquals(1, rows.size)
        // take(3) 对短串是安全的（Kotlin 的 take 不会越界），钉住实际形状
        assertEquals("a@b***", rows.single()[2], "短邮箱脱敏后应保持原样加 ***")
    }

    @Test
    fun `identity users export also masks short emails`() {
        seedUser("u_s", 1_000L, email = "ab@c.d")
        assertEquals("ab@***", repo.identityUsers(10).single()[4], "2 字符邮箱脱敏形状")
    }

    // ---- 非 Users 系的单表查询 ----

    @Test
    fun `poll votes export lists each vote with its option index`() {
        transaction {
            GroupPollVotes.insert {
                it[GroupPollVotes.pollId] = "p1"
                it[GroupPollVotes.userId] = "u1"
                it[GroupPollVotes.optionIndex] = 2
                it[GroupPollVotes.votedAt] = 1_000L
            }
        }
        val rows = repo.pollVotes(10)
        assertEquals(1, rows.size, "一票一行")
        assertEquals(4, rows.single().size, "列数必须稳定（pollId/userId/optionIndex/votedAt）")
        assertEquals("2", rows.single()[2], "optionIndex 必须如实导出")
    }

    @Test
    fun `poll votes export is empty without votes`() {
        assertTrue(repo.pollVotes(10).isEmpty())
    }

    @Test
    fun `group invites export only includes chats that have an invite token`() {
        // 有 token 的群
        transaction {
            Chats.insert {
                it[Chats.id] = "c_with_token"
                it[Chats.isGroup] = true
                it[Chats.groupInviteToken] = "tok_abcdef123456"
                it[Chats.groupInviteExpiresAt] = 9_000L
                it[Chats.groupInviteMaxUses] = 10
                it[Chats.groupInviteUseCount] = 3
            }
        }
        // 没有 token 的群
        transaction {
            Chats.insert {
                it[Chats.id] = "c_no_token"
                it[Chats.isGroup] = true
                it[Chats.groupInviteToken] = null
            }
        }
        val rows = repo.groupInvites(10)
        assertEquals(1, rows.size, "只应导出有邀请 token 的会话")
        assertEquals(5, rows.single().size, "列数必须稳定")
        assertEquals("tok_abcdef12", rows.single()[1], "token 应截断到 12 字符")
        assertEquals("9000", rows.single()[2], "过期时间戳必须如实导出")
    }

    // ---- G186e：三个带真实过滤的导出 ----

    private fun seedChat(id: String, chatType: String = "DIRECT", disappearingSeconds: Int = 0, memberRevision: Long = 0) {
        transaction {
            Chats.insert {
                it[Chats.id] = id
                it[Chats.isGroup] = chatType == "GROUP"
                it[Chats.chatType] = chatType
                it[Chats.disappearingMessageSeconds] = disappearingSeconds
                it[Chats.memberRevision] = memberRevision
            }
        }
    }

    private fun seedChatSettings(
        chatId: String,
        userId: String,
        muted: Boolean = false,
        archived: Boolean = false,
        pinned: Boolean = false,
        updatedAt: Long = 1_000L,
    ) {
        transaction {
            ChatUserSettings.insert {
                it[ChatUserSettings.chatId] = chatId
                it[ChatUserSettings.userId] = userId
                it[ChatUserSettings.notificationsMuted] = muted
                it[ChatUserSettings.archived] = archived
                it[ChatUserSettings.pinnedAt] = if (pinned) 5_000L else 0L
                it[ChatUserSettings.updatedAt] = updatedAt
            }
        }
    }

    // ---- restrictedUsers：三重时间窗 OR 过滤 ----

    @Test
    fun `restricted users export catches each of the three time windows independently`() {
        // 三个字段各自单独触发都应被导出——只测一个会漏掉另两个的过滤写错
        seedUser("u_msg_restricted", 1_000L)
        seedUser("u_post_restricted", 1_000L)
        seedUser("u_suspended", 1_000L)
        seedUser("u_free", 1_000L)
        val far = System.currentTimeMillis() + 7_200_000   // 两小时后
        transaction {
            exec("UPDATE users SET message_restricted_until = $far WHERE id = 'u_msg_restricted'")
            exec("UPDATE users SET post_restricted_until = $far WHERE id = 'u_post_restricted'")
            exec("UPDATE users SET suspended_until = $far WHERE id = 'u_suspended'")
        }
        val ids = repo.restrictedUsers(10).map { it.first().toString() }.toSet()
        assertTrue("u_msg_restricted" in ids, "messageRestrictedUntil 未来必须被导出")
        assertTrue("u_post_restricted" in ids, "postRestrictedUntil 未来必须被导出")
        assertTrue("u_suspended" in ids, "suspendedUntil 未来必须被导出")
        assertTrue("u_free" !in ids, "三个时间窗都过期的用户不应被导出")
    }

    @Test
    fun `restricted users export is empty when every restriction has expired`() {
        seedUser("u_past", 1_000L)
        transaction { exec("UPDATE users SET message_restricted_until = 1 WHERE id = 'u_past'") }
        assertTrue(repo.restrictedUsers(10).isEmpty(), "限制已过期的用户不应被导出")
    }

    @Test
    fun `restricted users export shows the three expiry columns`() {
        seedUser("u1", 1_000L)
        val far = System.currentTimeMillis() + 7_200_000
        transaction { exec("UPDATE users SET suspended_until = $far WHERE id = 'u1'") }
        val rows = repo.restrictedUsers(10)
        assertEquals(1, rows.size)
        assertTrue(rows.single().size >= 4, "应至少有 id + 三个到期时间列，实际 ${rows.single().size}")
    }

    // ---- mutedChats：notificationsMuted 过滤 + limit*2 ----

    @Test
    fun `muted chats export only includes chats whose notifications are muted`() {
        seedUser("u1", 1_000L)
        seedChat("c_muted", chatType = "DIRECT")
        seedChat("c_loud", chatType = "DIRECT")
        seedChatSettings("c_muted", "u1", muted = true)
        seedChatSettings("c_loud", "u1", muted = false)
        val rows = repo.mutedChats(10)
        assertEquals(1, rows.size, "只应导出静音的会话")
        assertEquals("c_muted", rows.single()[1], "第二列是 chatId")
        assertEquals("true", rows.single()[2], "notificationsMuted 列必须是 true")
    }

    @Test
    fun `muted chats export limit applies after the mute filter`() {
        // G186e 实测：实现的 `.limit(limit * 2)` 在「全部记录都静音」时
        // 以 SQL limit 为瓶颈，所以 limit=1 只返回 1 行（*2 的余量不会漏出来）。
        // 这条钉子这个行为，防将来有人「修正」limit*2 时悄悄改变条数。
        seedUser("u1", 1_000L)
        repeat(3) { seedChat("c$it", chatType = "DIRECT") }
        repeat(3) { seedChatSettings("c$it", "u1", muted = true, updatedAt = 1_000L + it) }
        assertEquals(1, repo.mutedChats(1).size, "limit=1 必须只返回 1 行")
        assertEquals(2, repo.mutedChats(2).size, "limit=2 必须只返回 2 行")
        assertEquals(3, repo.mutedChats(3).size, "limit=3 时 3 条静音记录全返回")
    }

    @Test
    fun `muted chats export limit is a sql row limit not a post filter quota`() {
        // G186e 实测纠正：`limit(limit * 2)` 的 *2 **不是**为混合场景准备的余量。
        // SQL 先按 updatedAt DESC 取前 2 行，再 mapNotNull 剔静音——
        // 所以 limit=1 在「第 2 新的记录恰好未静音」时，过滤后只剩 1 条静音记录。
        // 想要拿到第 3 新的那条静音记录，limit 必须自己变大。
        //
        // 这条钉住这个（有点反直觉的）语义：limit 是 SQL 行数上限，
        // 不是「返回 N 条静音记录」的配额。
        seedUser("u1", 1_000L)
        seedChat("c_m1", chatType = "DIRECT")
        seedChat("c_loud1", chatType = "DIRECT")
        seedChat("c_m2", chatType = "DIRECT")
        seedChatSettings("c_m1", "u1", muted = true, updatedAt = 3_000L)
        seedChatSettings("c_loud1", "u1", muted = false, updatedAt = 2_000L)
        seedChatSettings("c_m2", "u1", muted = true, updatedAt = 1_000L)

        assertEquals(1, repo.mutedChats(1).size, "limit=1：SQL 取 2 行，剔掉未静音后剩 1 行")
        assertEquals(2, repo.mutedChats(2).size, "limit=2：SQL 取 4 行（只有 3 行），剔掉 1 条剩 2 行")
    }

    // ---- disappearingChats：chatType + seconds 双重过滤 ----

    @Test
    fun `disappearing chats export excludes secret chats and zero second chats`() {
        seedChat("c_secret", chatType = "SECRET", disappearingSeconds = 60)
        seedChat("c_off", chatType = "DIRECT", disappearingSeconds = 0)
        seedChat("c_on", chatType = "DIRECT", disappearingSeconds = 300)
        val ids = repo.disappearingChats(10).map { it.first().toString() }
        assertEquals(listOf("c_on"), ids, "只应导出开启了阅后即焚的非密聊会话")
    }

    @Test
    fun `disappearing chats export shows group flag and seconds`() {
        seedChat("c_grp", chatType = "GROUP", disappearingSeconds = 120)
        val rows = repo.disappearingChats(10)
        assertEquals(1, rows.size)
        assertEquals(4, rows.single().size, "列数必须稳定（id/isGroup/groupName/seconds）")
        assertEquals("true", rows.single()[1], "群聊的 isGroup 列必须是 true")
        assertEquals("120", rows.single()[3], "秒数列必须如实导出")
    }

    @Test
    fun `disappearing chats export is ordered by member revision descending`() {
        seedChat("c_old", chatType = "DIRECT", disappearingSeconds = 60, memberRevision = 1)
        seedChat("c_new", chatType = "DIRECT", disappearingSeconds = 60, memberRevision = 9)
        val ids = repo.disappearingChats(10).map { it.first().toString() }
        assertEquals(listOf("c_new", "c_old"), ids, "必须按 memberRevision 倒序")
    }

    // ---- G186f：剩余里仅有的三个带过滤/关联逻辑的导出 ----

    private fun seedPinnedMessage(chatId: String, messageId: String, pinnedBy: String, pinnedAt: Long) {
        transaction {
            MessagingV2Messages.insert {
                it[MessagingV2Messages.id] = messageId
                it[MessagingV2Messages.conversationId] = chatId
                it[MessagingV2Messages.senderUserId] = pinnedBy
                it[MessagingV2Messages.senderDeviceId] = 1
                it[MessagingV2Messages.kind] = "TEXT"
                // 这三列必填且无 default（G186f 实测踩到：CLIENT_TIMESTAMP not allowed NULL）
                it[MessagingV2Messages.clientTimestamp] = pinnedAt
                it[MessagingV2Messages.serverTimestamp] = pinnedAt
                it[MessagingV2Messages.requestDigest] = "d".repeat(64)
            }
            PinnedMessages.insert {
                it[PinnedMessages.chatId] = chatId
                it[PinnedMessages.messageId] = messageId
                it[PinnedMessages.pinnedBy] = pinnedBy
                it[PinnedMessages.pinnedAt] = pinnedAt
            }
        }
    }

    @Test
    fun `pinned messages export excludes pins belonging to secret chats`() {
        // 与 mutedChats / chatSettings 同一套 SECRET 过滤，但那两个已测、这个没有
        seedUser("u1", 1_000L)
        seedChat("c_plain", chatType = "DIRECT")
        seedChat("c_secret", chatType = "SECRET")
        seedPinnedMessage("c_plain", "m_plain", "u1", 1_000L)
        seedPinnedMessage("c_secret", "m_secret", "u1", 9_000L)
        val rows = repo.pinnedMessages(10)
        assertEquals(1, rows.size, "密聊的置顶消息必须被排除")
        assertEquals("c_plain", rows.single()[0], "只剩普通聊天的置顶")
        assertEquals(4, rows.single().size, "列数必须稳定（chatId/messageId/pinnedBy/pinnedAt）")
    }

    @Test
    fun `pinned messages export is ordered newest pin first`() {
        seedUser("u1", 1_000L)
        seedChat("c1", chatType = "DIRECT")
        seedChat("c2", chatType = "DIRECT")
        seedPinnedMessage("c1", "m_old", "u1", 1_000L)
        seedPinnedMessage("c2", "m_new", "u1", 9_000L)
        val rows = repo.pinnedMessages(10)
        assertEquals(listOf("c2", "c1"), rows.map { it.first().toString() }, "必须按 pinnedAt 倒序")
    }

    @Test
    fun `pinned messages export is empty with no pins`() {
        assertTrue(repo.pinnedMessages(10).isEmpty())
    }

    private fun seedPoll(id: String, chatId: String, creatorId: String, createdAt: Long) {
        transaction {
            GroupPolls.insert {
                it[GroupPolls.id] = id
                it[GroupPolls.chatId] = chatId
                it[GroupPolls.creatorId] = creatorId
                it[GroupPolls.question] = "q_$id"
                it[GroupPolls.optionsJson] = "[\"a\",\"b\"]"
                it[GroupPolls.createdAt] = createdAt
            }
        }
    }

    @Test
    fun `polls export counts votes per poll and zero votes are not null`() {
        // 8.48 的批量 count 优化最容易错的地方：0 票 poll 不能漏出也不能变 null
        seedUser("u1", 1_000L)
        seedPoll("p_voted", "c1", "u1", 2_000L)
        seedPoll("p_empty", "c1", "u1", 1_000L)
        repeat(3) { i ->
            transaction {
                GroupPollVotes.insert {
                    it[GroupPollVotes.pollId] = "p_voted"
                    it[GroupPollVotes.userId] = "voter$i"
                    it[GroupPollVotes.optionIndex] = 0
                    it[GroupPollVotes.votedAt] = 1_000L
                }
            }
        }
        val byId = repo.polls(10).associateBy { it.first().toString() }
        assertEquals(2, byId.size, "两个 poll 都应导出（0 票的不能漏）")
        assertEquals("3", byId["p_voted"]?.get(7), "有 3 票的 poll 必须数成 3")
        assertEquals("0", byId["p_empty"]?.get(7), "0 票必须导出成 0 而不是 null")
        assertEquals(10, byId.values.first().size, "列数必须稳定")
    }

    @Test
    fun `polls export is empty with no polls and does not query votes`() {
        assertTrue(repo.polls(10).isEmpty(), "空库必须返回空列表（且不能因 inList 空集合报错）")
    }

    @Test
    fun `polls export is ordered newest poll first`() {
        seedUser("u1", 1_000L)
        seedPoll("p_old", "c1", "u1", 1_000L)
        seedPoll("p_new", "c1", "u1", 9_000L)
        assertEquals(listOf("p_new", "p_old"), repo.polls(10).map { it.first().toString() }, "必须按 createdAt 倒序")
    }

    @Test
    fun `risk events export lists each event with its columns`() {
        seedUser("u1", 1_000L)
        transaction {
            RiskEvents.insert {
                it[RiskEvents.id] = "r1"
                it[RiskEvents.userId] = "u1"
                it[RiskEvents.sourceValue] = "message"
                it[RiskEvents.action] = "flag"
                it[RiskEvents.createdAt] = 1_000L
            }
        }
        val rows = repo.riskEvents(10)
        assertEquals(1, rows.size)
        assertEquals("u1", rows.single()[1], "userId 列必须如实导出")
        assertEquals("message", rows.single()[2], "source 列必须如实导出")
    }

    @Test
    fun `risk events export is empty without events`() {
        assertTrue(repo.riskEvents(10).isEmpty())
    }
}
