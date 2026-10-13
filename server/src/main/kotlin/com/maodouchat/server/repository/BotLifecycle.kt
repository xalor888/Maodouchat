package com.maodouchat.server.repository

// bot 生命周期簇（创建/删除/启停/webhook 设置/管理查询）：原 BotRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.deleteMessagingV2ParticipantStateInTx
import com.maodouchat.server.service.isUniqueViolation
import com.maodouchat.server.service.maxBotsPerUser
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

fun BotRepository.create(ownerUserId: String, name: String, username: String, description: String?): BotRepository.BotCreateResult {
    val n = name.trim().take(120)
    val u = normalizeUsername(username) ?: return BotRepository.BotCreateResult.InvalidInput
    if (n.isBlank()) return BotRepository.BotCreateResult.InvalidInput
    val maxBots = try {
        com.maodouchat.server.service.RuntimeConfigService.maxBotsPerUser()
    } catch (_: Exception) { 20 }
    val id = "bot_" + UUID.randomUUID().toString().replace("-", "").take(16)
    val token = generateToken()
    val now = System.currentTimeMillis()
    return try {
        transaction {
            val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
                ?: return@transaction BotRepository.BotCreateResult.OwnerInvalid
            if (owner[Users.deletedAt] != null) return@transaction BotRepository.BotCreateResult.OwnerInvalid
            if (owner[Users.suspendedUntil] > now) return@transaction BotRepository.BotCreateResult.OwnerInvalid
            val owned = BotApps.selectAll().where { BotApps.ownerUserId eq ownerUserId }.count()
            if (owned >= maxBots.coerceAtLeast(0).toLong()) return@transaction BotRepository.BotCreateResult.MaxBotsReached
            if (BotApps.selectAll().where { BotApps.username eq u }.firstOrNull() != null) {
                return@transaction BotRepository.BotCreateResult.UsernameTaken
            }
            // Bot identity is also a Users row so message.senderId FK remains valid.
            Users.insert {
                it[Users.id] = id
                it[Users.email] = "$id@bots.maodouchat.local"
                it[Users.passwordHash] = "!" // unusable password
                it[Users.name] = n
                it[Users.searchable] = false
                it[Users.lastSeen] = now
            }
            BotApps.insert {
                it[BotApps.id] = id
                it[BotApps.ownerUserId] = ownerUserId
                it[BotApps.name] = n
                it[BotApps.username] = u
                it[BotApps.description] = description?.trim()?.take(500)
                it[BotApps.tokenHash] = hashToken(token)
                it[BotApps.tokenPrefix] = token.take(8)
                it[BotApps.webhookUrl] = null
                it[BotApps.enabled] = true
                it[BotApps.commandsJson] = encodeCommands(DEFAULT_COMMANDS)
                it[BotApps.createdAt] = now
                it[BotApps.updatedAt] = now
            }
            BotRepository.BotCreateResult.Success(
                BotApps.selectAll().where { BotApps.id eq id }.first().toDto().copy(tokenOnce = token)
            )
        }
    } catch (error: Exception) {
        if (isUniqueViolation(error)) BotRepository.BotCreateResult.UsernameTaken else throw error
    }
}

fun BotRepository.regenerateToken(botId: String, ownerUserId: String): BotRepository.BotDto? {
    val token = generateToken()
    val now = System.currentTimeMillis()
    return transaction {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > now) return@transaction null
        val updated = BotApps.update({
            (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
        }) {
            it[tokenHash] = hashToken(token)
            it[tokenPrefix] = token.take(8)
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto().copy(tokenOnce = token)
    }
}

fun BotRepository.setWebhook(botId: String, ownerUserId: String, url: String?): BotRepository.BotDto? {
    val clean = url?.trim()?.take(500)
    if (!clean.isNullOrBlank() && !isAllowedWebhookUrl(clean)) {
        return null
    }
    val now = System.currentTimeMillis()
    return transaction {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > now) return@transaction null
        val updated = BotApps.update({
            (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
        }) {
            it[webhookUrl] = clean
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}

fun BotRepository.setWebhookByToken(botId: String, url: String?): BotRepository.BotDto? {
    val clean = url?.trim()?.take(500)
    if (!clean.isNullOrBlank() && !isAllowedWebhookUrl(clean)) {
        return null
    }
    val now = System.currentTimeMillis()
    return transaction {
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate().firstOrNull()
            ?: return@transaction null
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
        val updated = BotApps.update({ BotApps.id eq botId }) {
            it[webhookUrl] = clean
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}

fun BotRepository.setEnabled(botId: String, ownerUserId: String, enabled: Boolean): BotRepository.BotDto? {
    val now = System.currentTimeMillis()
    return transaction {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > now) return@transaction null
        val updated = BotApps.update({
            (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
        }) {
            it[BotApps.enabled] = enabled
            it[updatedAt] = now
        }
        if (updated != 1) return@transaction null
        BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
    }
}

fun BotRepository.delete(botId: String, ownerUserId: String): Boolean {
    return transaction {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
            ?: return@transaction false
        if (owner[Users.deletedAt] != null) return@transaction false
        if (owner[Users.suspendedUntil] > System.currentTimeMillis()) return@transaction false
        BotApps.selectAll().where {
            (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
        }.forUpdate().firstOrNull() ?: return@transaction false
        val chatIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId eq botId }
            .map { it[ChatParticipants.chatId] }
            .distinct()
            .sorted()
        val lockedChats = if (chatIds.isEmpty()) emptyList() else Chats.selectAll()
            .where { Chats.id inList chatIds }
            .orderBy(Chats.id to org.jetbrains.exposed.sql.SortOrder.ASC)
            .forUpdate()
            .toList()
        // 8.37：bot 可能是群主——删除前先转移所有权（与 UserRepository.removeOwnedBots 一致：
        // ADMIN > MEMBER > joinedAt 选后继 + OWNERSHIP_TRANSFERRED 审计），避免群变无主
        val now = System.currentTimeMillis()
        lockedChats.filter { it[Chats.isGroup] }.forEach { chat ->
            val chatId = chat[Chats.id]
            val participants = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .toList()
            if (participants.any { it[ChatParticipants.userId] == botId && it[ChatParticipants.role] == "OWNER" }) {
                val successor = participants
                    .filterNot { it[ChatParticipants.userId] == botId }
                    .sortedWith(
                        compareByDescending<org.jetbrains.exposed.sql.ResultRow> {
                            when (it[ChatParticipants.role]) {
                                "ADMIN" -> 2
                                "MEMBER" -> 1
                                else -> 0
                            }
                        }.thenBy { it[ChatParticipants.joinedAt] }
                            .thenBy { it[ChatParticipants.userId] }
                    )
                    .firstOrNull()
                if (successor != null) {
                    val successorId = successor[ChatParticipants.userId]
                    ChatParticipants.update({
                        (ChatParticipants.chatId eq chatId) and
                            (ChatParticipants.userId eq successorId)
                    }) { it[ChatParticipants.role] = "OWNER" }
                    GroupAuditLogs.insert {
                        it[GroupAuditLogs.id] = "gal_${java.util.UUID.randomUUID()}"
                        it[GroupAuditLogs.chatId] = chatId
                        it[GroupAuditLogs.actorId] = ownerUserId
                        it[GroupAuditLogs.action] = "OWNERSHIP_TRANSFERRED"
                        it[GroupAuditLogs.targetUserId] = successorId
                        it[GroupAuditLogs.createdAt] = now
                    }
                }
            }
        }
        // Drop memberships first so history/fanout no longer targets the bot identity.
        lockedChats.forEach { chat ->
            deleteMessagingV2ParticipantStateInTx(chat[Chats.id], botId)
        }
        ChatUserSettings.deleteWhere { ChatUserSettings.userId eq botId }
        ChatParticipants.deleteWhere { ChatParticipants.userId eq botId }
        // 9.124：bot 可经 /api/bot/sendPoll 以自身身份创建群投票——删除 bot 时连投票
        // 及其选项投票一并清掉（与 UserRepository.removeOwnedBots 的 deletePollsCreatedBy 对齐），
        // 否则群内残留创建者已注销的孤儿投票。
        val botPollIds = GroupPolls.select(GroupPolls.id)
            .where { GroupPolls.creatorId eq botId }
            .orderBy(GroupPolls.id to org.jetbrains.exposed.sql.SortOrder.ASC)
            .forUpdate()
            .map { it[GroupPolls.id] }
        if (botPollIds.isNotEmpty()) {
            GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList botPollIds }
            GroupPolls.deleteWhere { GroupPolls.id inList botPollIds }
        }
        GroupPollVotes.deleteWhere { GroupPollVotes.userId eq botId }
        StarMessages.deleteWhere { StarMessages.userId eq botId }
        ServiceMessageReactions.deleteWhere { ServiceMessageReactions.botUserId eq botId }
        lockedChats.filter { it[Chats.isGroup] }.forEach { chat ->
            Chats.update({ Chats.id eq chat[Chats.id] }) {
                it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
            }
        }
        BotCommandLogs.deleteWhere {
            (BotCommandLogs.botId eq botId) or (BotCommandLogs.userId eq botId)
        }
        BotUpdateInbox.deleteWhere { BotUpdateInbox.botId eq botId }
        if (BotApps.deleteWhere {
                (BotApps.id eq botId) and (BotApps.ownerUserId eq ownerUserId)
            } != 1
        ) return@transaction false
        // Keep a tombstone so historical v2 sender attribution remains stable.
        Users.update({ Users.id eq botId }) {
            it[Users.name] = "deleted-bot"
            it[Users.status] = ""
            it[Users.avatar] = null
            it[Users.isOnline] = false
            it[Users.showOnline] = false
            it[Users.showStatus] = false
            it[Users.searchable] = false
            it[Users.lastSeen] = 0
            it[Users.deletedAt] = System.currentTimeMillis()
        }
        true
    }
}

/** 8.33：bot 所在全部会话（删除 bot 后需要向这些群广播 memberRevision）。 */
fun BotRepository.groupChatIdsFor(botId: String): List<String> = transaction {
    ChatParticipants.select(ChatParticipants.chatId)
        .where { ChatParticipants.userId eq botId }
        .map { it[ChatParticipants.chatId] }
        .distinct()
}

/**
 * 管理后台专用启停：不校验 owner（管理员跨 owner 操作），只按 botId 更新并回读。
 * 返回 null 表示 bot 不存在。此前这段写在 `plugins/AdminDiagnosticsRouting.kt` 的裸事务里。
 */
fun BotRepository.setAdminEnabled(botId: String, enabled: Boolean): BotRepository.BotDto? = transaction {
    val updated = BotApps.update({ BotApps.id eq botId }) {
        it[BotApps.enabled] = enabled
        it[updatedAt] = System.currentTimeMillis()
    }
    if (updated != 1) return@transaction null
    BotApps.selectAll().where { BotApps.id eq botId }.firstOrNull()?.toDto()
}

fun BotRepository.adminList(limit: Int = 50, offset: Int = 0): List<BotRepository.BotDto> = transaction {
    BotApps.selectAll()
        .orderBy(
            BotApps.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            BotApps.id to org.jetbrains.exposed.sql.SortOrder.DESC
        )
        .limit(limit.coerceIn(1, 200), offset.toLong().coerceAtLeast(0))
        .map { it.toDto() }
}
