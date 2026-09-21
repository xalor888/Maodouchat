package com.maodouchat.server.repository

import com.maodouchat.server.common.toPostAdminResponse
import com.maodouchat.server.common.toUserAdminResponse
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatAdminResponse
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CommentAdminResponse
import com.maodouchat.server.model.OpsSnapshotResponse
import com.maodouchat.server.model.PostAdminResponse
import com.maodouchat.server.model.PushTokenAdminResponse
import com.maodouchat.server.model.RiskEventAdminResponse
import com.maodouchat.server.model.UserAdminResponse
import com.maodouchat.server.model.UserDetailAdminResponse
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 会话总览里的 signal 设备条目。 */
data class AdminSignalDeviceRow(
    val deviceId: Int,
    val deviceName: String,
    val status: String,
    val lastSeenAt: Long,
    val createdAt: Long,
)

/** 会话总览里的 push token 条目。 */
data class AdminPushTokenRow(
    val deviceId: String,
    val platform: String,
    val updatedAt: Long,
)

/**
 * 管理搜索返回的消息**元数据**。
 *
 * 刻意没有「内容」字段：人类消息正文对服务端不透明，这里永远读不到、也不该读。
 */
data class AdminMessageMetadataRow(
    val id: String,
    val chatId: String,
    val senderId: String,
    val kind: String,
    val timestamp: Long,
)

data class AdminMessageSearchFilter(
    val q: String,
    val chatId: String,
    val userId: String,
    val limit: Int,
    val offset: Int,
)

/**
 * M2/G15：管理后台各路由里直写事务的归属地。
 *
 * 职责：**本类只负责「取数据 / 写审计」**，响应 JSON 的形状仍由路由决定——路由管 HTTP，
 * repository 管 SQL。这一条是 `ServerArchitectureTest` 的棘轮在守的契约。
 *
 * 各方法的 SQL 都是从对应路由的 handler 内**逐字**搬过来的（搬迁前后行为一致），
 * 唯一的改动是把 `Chats` / `RiskEvents` 等表的列名对齐到 `db/CoreTables.kt` 的真实定义。
 */
class AdminManagementRepository {

    // ------------------------------------------------------------------
    // 会话总览
    // ------------------------------------------------------------------

    fun signalDevices(userId: String): List<AdminSignalDeviceRow> = transaction {
        SignalDevices.selectAll()
            .where { SignalDevices.userId eq userId }
            .orderBy(SignalDevices.lastSeenAt to SortOrder.DESC)
            .map {
                AdminSignalDeviceRow(
                    deviceId = it[SignalDevices.deviceId],
                    deviceName = it[SignalDevices.deviceName],
                    status = it[SignalDevices.status],
                    lastSeenAt = it[SignalDevices.lastSeenAt],
                    createdAt = it[SignalDevices.createdAt],
                )
            }
    }

    fun pushTokens(userId: String): List<AdminPushTokenRow> = transaction {
        PushTokens.selectAll()
            .where { PushTokens.userId eq userId }
            .orderBy(PushTokens.updatedAt to SortOrder.DESC)
            .map {
                AdminPushTokenRow(
                    deviceId = it[PushTokens.deviceId],
                    platform = it[PushTokens.platform],
                    updatedAt = it[PushTokens.updatedAt],
                )
            }
    }

    /**
     * 批量写审计行（批量管理端点一次要落 N 条）。
     *
     * 从 `plugins/AdminBulkRouting.kt` 的裸 `ModerationAuditLog.batchInsert` 下沉：
     * 批量操作逐条 insert 会带来 N 次往返，这里一次 batchInsert 保持原子。
     */
    fun recordAuditBatch(
        actorId: String,
        action: String,
        entries: List<Triple<String, String, Long>>,
    ) {
        if (entries.isEmpty()) return
        transaction {
            ModerationAuditLog.batchInsert(entries) { (userId, detail, createdAt) ->
                this[ModerationAuditLog.userId] = userId
                this[ModerationAuditLog.action] = action
                this[ModerationAuditLog.detail] = detail
                this[ModerationAuditLog.actorId] = actorId
                this[ModerationAuditLog.createdAt] = createdAt
            }
        }
    }

    /** Metadata-only search. Human payloads remain opaque to the server. */
    fun searchMessageMetadata(filter: AdminMessageSearchFilter): List<AdminMessageMetadataRow> = transaction {
        var query = MessagingV2Messages.selectAll()
        if (filter.chatId.isNotBlank()) {
            query = query.andWhere { MessagingV2Messages.conversationId eq filter.chatId }
        }
        if (filter.userId.isNotBlank()) {
            query = query.andWhere { MessagingV2Messages.senderUserId eq filter.userId }
        }
        query = query.andWhere {
            (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE) and
                (MessagingV2Messages.conversationId notInSubQuery (
                    Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
                ))
        }
        if (filter.q.isNotBlank()) {
            val like = "%" + escapeLikePattern(filter.q.take(80)) + "%"
            query = query.andWhere {
                (MessagingV2Messages.id like like) or
                    (MessagingV2Messages.conversationId like like) or
                    (MessagingV2Messages.senderUserId like like) or
                    (MessagingV2Messages.kind like like)
            }
        }
        query.orderBy(
            MessagingV2Messages.serverTimestamp to SortOrder.DESC,
            MessagingV2Messages.id to SortOrder.DESC,
        )
            .limit(filter.limit, filter.offset.toLong())
            .map {
                AdminMessageMetadataRow(
                    id = it[MessagingV2Messages.id],
                    chatId = it[MessagingV2Messages.conversationId],
                    senderId = it[MessagingV2Messages.senderUserId],
                    kind = it[MessagingV2Messages.kind],
                    timestamp = it[MessagingV2Messages.serverTimestamp],
                )
            }
    }

    /**
     * 三处路由都只是「写一行审计」，收敛到这一个 owner。
     * `userId` 为 null 表示这条审计不对应某个具体用户（例如广播）。
     */
    fun recordAudit(actorId: String?, userId: String?, action: String, detail: String?) {
        transaction {
            ModerationAuditLog.insert {
                it[ModerationAuditLog.actorId] = actorId
                it[ModerationAuditLog.userId] = userId
                it[ModerationAuditLog.action] = action
                it[ModerationAuditLog.detail] = detail
                it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
            }
        }
    }

    /**
     * 后台「安全快照」的两个计数：**用户总数**与**待复核风控事件数**。
     *
     * 原先内联在 `AdminSystemRouting` 的 handler 里。HEAD 版本还顺带算了一个
     * `activeSessions`（写成恒为 0 的 best-effort 占位，注释就是 "table may vary"），
     * 但那个值**从未进入响应体**，所以这里不再保留——删掉的是死代码，不是字段。
     *
     * 风控计数保留 `runCatching`：老库可能还没有 `risk_events` 表，
     * 那种情况下快照应当照常返回，而不是整个接口 500。
     */
    fun systemOverviewStats(): Pair<Long, Long> = transaction {
        val totalUsers = Users.selectAll().count()
        val openRiskEvents = runCatching {
            RiskEvents.selectAll().where { RiskEvents.needsReview eq true }.count()
        }.getOrDefault(0L)
        totalUsers to openRiskEvents
    }

    // ------------------------------------------------------------------
    // 群聊管理（原 AdminChatsRouting 的 handler 内联事务）
    // ------------------------------------------------------------------

    /**
     * 群聊列表。`memberRevision DESC` 让最近有过成员变动的群排在前面，与 HEAD 的内联实现一致。
     *
     * 成员数与最近活跃时间都用**一次分组查询**取回，避免 N+1（这也是原实现的做法）。
     */
    fun listAdminChats(
        limit: Int,
        offset: Long,
        groupOnly: Boolean,
        search: String?,
    ): List<ChatAdminResponse> = transaction {
        val query = Chats.selectAll()
        query.andWhere { Chats.chatType neq ChatType.SECRET }
        if (groupOnly) query.andWhere { Chats.isGroup eq true }
        val escapedSearch = search?.let { escapeLikePattern(it) }
        if (escapedSearch != null) query.andWhere { Chats.groupName like "%$escapedSearch%" }

        val rows = query
            .orderBy(Chats.memberRevision to SortOrder.DESC, Chats.id to SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        val chatIds = rows.map { it[Chats.id] }

        val countExpr = ChatParticipants.userId.count()
        val memberCounts: Map<String, Int> = if (chatIds.isEmpty()) {
            emptyMap()
        } else {
            ChatParticipants.slice(ChatParticipants.chatId, countExpr)
                .selectAll().where { ChatParticipants.chatId inList chatIds }
                .groupBy(ChatParticipants.chatId)
                .associate { it[ChatParticipants.chatId] to it[countExpr].toInt() }
        }

        val maxExpr = MessagingV2Messages.serverTimestamp.max()
        val lastMsgMap: Map<String, Long> = if (chatIds.isEmpty()) {
            emptyMap()
        } else {
            MessagingV2Messages.slice(MessagingV2Messages.conversationId, maxExpr)
                .selectAll().where {
                    (MessagingV2Messages.conversationId inList chatIds) and
                        (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
                }
                .groupBy(MessagingV2Messages.conversationId)
                .associate { it[MessagingV2Messages.conversationId] to (it[maxExpr] ?: 0L) }
        }

        rows.map { row ->
            val chatId = row[Chats.id]
            ChatAdminResponse(
                id = chatId,
                isGroup = row[Chats.isGroup],
                chatType = row[Chats.chatType],
                groupName = row[Chats.groupName],
                groupAnnouncement = row[Chats.groupAnnouncement],
                memberCount = memberCounts[chatId] ?: 0,
                createdAt = 0L,
                lastActivity = lastMsgMap[chatId] ?: 0L,
            )
        }
    }

    /**
     * 解散群聊/频道。
     *
     * 级联清理统一走 [ConversationStateDeletion.deleteConversation]（含邀请、投票、Messaging V2
     * 等全部关联表），避免管理端绕过领域删除规则、漏删 GroupInvitations/GroupPolls 造成孤儿数据。
     *
     * 返回 `(状态, 需清理的附件 id, 群头像 URL)`：状态不是 `ok` 时后两项无意义；
     * **磁盘清理留给调用方在事务外做**——文件系统失败不该回滚一个已经成立的解散事实。
     *
     * 注意：这里**不写 `Chats.status`**。`Chats` 表没有 `status` 列，「已解散」是由
     * `deleteConversation` 删掉全部相关行来表达的，路由返回的 `{"status":"dissolved"}` 是响应体。
     */
    fun dissolveGroupChat(chatId: String): Triple<String, List<String>, String?> = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction Triple("missing", emptyList<String>(), null as String?)
        if (!chat[Chats.isGroup] || chat[Chats.chatType] == ChatType.SECRET) {
            return@transaction Triple("forbidden", emptyList<String>(), null)
        }
        val attachmentIds = ConversationStateDeletion.deleteConversation(chatId)
        Triple("ok", attachmentIds, chat[Chats.groupAvatar])
    }

    // ------------------------------------------------------------------
    // 内容管理（原 AdminContentRouting 的 handler 内联事务）
    // ------------------------------------------------------------------

    fun listAdminPosts(
        limit: Int,
        offset: Long,
        authorId: String?,
        status: String?,
        search: String?,
    ): List<PostAdminResponse> = transaction {
        val query = Posts.selectAll()
        if (!authorId.isNullOrBlank()) query.andWhere { Posts.authorId eq authorId }
        if (!status.isNullOrBlank()) query.andWhere { Posts.status eq status }
        val escapedSearch = search?.let { escapeLikePattern(it) }
        if (escapedSearch != null) query.andWhere { Posts.content like "%$escapedSearch%" }
        val rows = query
            .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        val authorIds = rows.map { it[Posts.authorId] }.distinct()
        val authorNames = if (authorIds.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll()
                .where { Users.id inList authorIds }
                .associate { it[Users.id] to it[Users.name] }
        }
        rows.map { it.toPostAdminResponse(authorNames[it[Posts.authorId]].orEmpty()) }
    }

    fun listAdminComments(
        limit: Int,
        offset: Long,
        search: String?,
    ): List<CommentAdminResponse> = transaction {
        val query = (PostComments innerJoin Users).selectAll()
        val escapedSearch = search?.let { escapeLikePattern(it) }
        if (escapedSearch != null) {
            query.andWhere {
                (PostComments.content like "%$escapedSearch%") or (Users.name like "%$escapedSearch%")
            }
        }
        query.orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
            .limit(limit, offset)
            .map {
                CommentAdminResponse(
                    id = it[PostComments.id],
                    postId = it[PostComments.postId],
                    authorId = it[PostComments.authorId],
                    authorName = it[Users.name],
                    content = it[PostComments.content],
                    createdAt = it[PostComments.createdAt],
                )
            }
    }

    // ------------------------------------------------------------------
    // 风控事件（原 AdminModerationRouting 的 handler 内联事务）
    // ------------------------------------------------------------------

    fun listRiskEvents(
        limit: Int,
        offset: Long,
        needsReviewOnly: Boolean,
    ): List<RiskEventAdminResponse> = transaction {
        val query = RiskEvents.selectAll()
        if (needsReviewOnly) query.andWhere { RiskEvents.needsReview eq true }
        query.orderBy(RiskEvents.createdAt to SortOrder.DESC, RiskEvents.id to SortOrder.DESC)
            .limit(limit, offset)
            .map {
                RiskEventAdminResponse(
                    id = it[RiskEvents.id],
                    userId = it[RiskEvents.userId],
                    source = it[RiskEvents.sourceValue],
                    ruleId = it[RiskEvents.ruleId],
                    action = it[RiskEvents.action],
                    matched = it[RiskEvents.matched],
                    referenceId = it[RiskEvents.referenceId],
                    needsReview = it[RiskEvents.needsReview],
                    createdAt = it[RiskEvents.createdAt],
                )
            }
    }

    fun resolveRiskEvent(id: String): Boolean = transaction {
        RiskEvents.selectAll().where { RiskEvents.id eq id }.firstOrNull()
            ?: return@transaction false
        RiskEvents.update({ RiskEvents.id eq id }) { it[needsReview] = false }
        true
    }

    // ------------------------------------------------------------------
    // 用户管理（原 AdminUsersRouting 的 handler 内联事务）
    // ------------------------------------------------------------------

    fun listUsers(
        limit: Int,
        offset: Long,
        search: String?,
        status: String?,
    ): List<UserAdminResponse> = transaction {
        val escapedSearch = search?.let { escapeLikePattern(it) }
        val base = if (escapedSearch != null) {
            val likePattern = LikePattern("%$escapedSearch%", '\\')
            Users.selectAll().where {
                (Users.name like likePattern) or (Users.email like likePattern)
            }
        } else {
            Users.selectAll()
        }
        val now = System.currentTimeMillis()
        val filtered = when (status) {
            "active" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil lessEq now) }
            "banned" -> base.andWhere { Users.deletedAt.isNull() and (Users.suspendedUntil greater now) }
            "deleted" -> base.andWhere { Users.deletedAt.isNotNull() }
            "online" -> base.andWhere { Users.deletedAt.isNull() and (Users.isOnline eq true) }
            // 无关键字的默认列表隐藏已注销；带 q 时包含 tombstone，方便按 deleted_ 邮箱找回。
            else -> if (escapedSearch != null) base else base.andWhere { Users.deletedAt.isNull() }
        }
        filtered.orderBy(Users.lastSeen to SortOrder.DESC, Users.id to SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toUserAdminResponse() }
    }

    fun getUserAdmin(id: String): UserAdminResponse? = transaction {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUserAdminResponse()
    }

    fun getUserDetail(id: String): UserDetailAdminResponse? = transaction {
        val row = Users.selectAll().where { Users.id eq id }.firstOrNull() ?: return@transaction null
        UserDetailAdminResponse(
            id = row[Users.id],
            name = row[Users.name],
            email = row[Users.email],
            isModerator = row[Users.isModerator],
            lastActiveAt = row[Users.lastSeen],
            suspendedUntil = row[Users.suspendedUntil],
            postRestrictedUntil = row[Users.postRestrictedUntil],
            messageRestrictedUntil = row[Users.messageRestrictedUntil],
            deletedAt = row[Users.deletedAt],
            messageCount = MessagingV2Messages.selectAll().where {
                (MessagingV2Messages.senderUserId eq id) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }.count(),
            postCount = Posts.selectAll().where { Posts.authorId eq id }.count(),
            commentCount = PostComments.selectAll().where { PostComments.authorId eq id }.count(),
            chatCount = ChatParticipants.selectAll().where { ChatParticipants.userId eq id }.count(),
            pushTokenCount = PushTokens.selectAll().where { PushTokens.userId eq id }.count(),
            reportCount = Reports.selectAll().where {
                (Reports.reporterId eq id) or (Reports.targetId eq id)
            }.count(),
            avatar = row[Users.avatar],
        )
    }

    /** 管理后台推送令牌列表（只含元数据，绝不含推送内容）。可选按 userId 精确过滤。 */
    fun listPushTokens(limit: Int, offset: Long, userIdFilter: String?): List<PushTokenAdminResponse> = transaction {
        val query = PushTokens.selectAll()
        if (!userIdFilter.isNullOrBlank()) query.andWhere { PushTokens.userId eq userIdFilter }
        query.orderBy(
            PushTokens.updatedAt to SortOrder.DESC,
            PushTokens.userId to SortOrder.DESC,
            PushTokens.deviceId to SortOrder.DESC,
        )
            .limit(limit, offset)
            .map {
                PushTokenAdminResponse(
                    userId = it[PushTokens.userId],
                    deviceId = it[PushTokens.deviceId],
                    platform = it[PushTokens.platform],
                    timezoneOffsetMinutes = it[PushTokens.timezoneOffsetMinutes],
                    updatedAt = it[PushTokens.updatedAt],
                )
            }
    }

    /**
     * 运维快照：机器人 / 群投票 / 消息与用户体量计数。
     * 只做只读聚合；此前这段写在 `plugins/AdminDiagnosticsRouting.kt` 的裸事务里。
     */
    fun opsSnapshot(generatedAt: Long = System.currentTimeMillis()): OpsSnapshotResponse = transaction {
        OpsSnapshotResponse(
            users = Users.selectAll().count(),
            messages = MessagingV2Messages.selectAll().where {
                MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
            }.count(),
            botsTotal = BotApps.selectAll().count(),
            botsEnabled = BotApps.selectAll().where { BotApps.enabled eq true }.count(),
            botsWithWebhook = BotApps.selectAll()
                .mapNotNull { row ->
                    val url = row[BotApps.webhookUrl]
                    val enabled = row[BotApps.enabled]
                    if (enabled && !url.isNullOrBlank()) 1 else null
                }.size.toLong(),
            pollsTotal = GroupPolls.selectAll().count(),
            pollsOpen = GroupPolls.selectAll().where { GroupPolls.closed eq false }.count(),
            pollVotes = GroupPollVotes.selectAll().count(),
            generatedAt = generatedAt,
        )
    }
}
