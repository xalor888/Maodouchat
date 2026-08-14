package com.maodouchat.server.repository

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.AiPreferences
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.SenderKeyDistributions
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object BotRepository {
    // 8.48 修复 L1：收件箱事件 JSON 上限——超过即拒写（take 截断会从多字节字符/JSON token
    // 中间切断产生损坏行，bot 轮询解析抛异常）。正常事件远小于该值。
    private const val MAX_UPDATE_JSON_CHARS = 16_000
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val logger = org.slf4j.LoggerFactory.getLogger(BotRepository::class.java)

    @Serializable
    data class BotDto(
        val id: String,
        val ownerUserId: String,
        val name: String,
        val username: String,
        val description: String? = null,
        val tokenPrefix: String,
        val webhookUrl: String? = null,
        val commandsJson: String? = null,
        val enabled: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val tokenOnce: String? = null // only returned on create/regenerate
    )

    @Serializable
    data class BotCommandDef(
        val command: String,
        val description: String
    )

    sealed interface BotCreateResult {
        data class Success(val bot: BotDto) : BotCreateResult
        data object InvalidInput : BotCreateResult
        data object UsernameTaken : BotCreateResult
        data object MaxBotsReached : BotCreateResult
        data object OwnerInvalid : BotCreateResult
    }

    fun listByOwner(ownerUserId: String): List<BotDto> = transaction {
        BotApps.selectAll().where { BotApps.ownerUserId eq ownerUserId }
            .orderBy(
                BotApps.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                BotApps.id to org.jetbrains.exposed.sql.SortOrder.DESC
            )
            .map { it.toDto() }
    }

    fun create(ownerUserId: String, name: String, username: String, description: String?): BotCreateResult {
        val n = name.trim().take(120)
        val u = normalizeUsername(username) ?: return BotCreateResult.InvalidInput
        if (n.isBlank()) return BotCreateResult.InvalidInput
        val maxBots = try {
            com.maodouchat.server.service.RuntimeConfigService.maxBotsPerUser()
        } catch (_: Exception) { 20 }
        val id = "bot_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val token = generateToken()
        val now = System.currentTimeMillis()
        return try {
            transaction {
                val owner = Users.selectAll().where { Users.id eq ownerUserId }.forUpdate().firstOrNull()
                    ?: return@transaction BotCreateResult.OwnerInvalid
                if (owner[Users.deletedAt] != null) return@transaction BotCreateResult.OwnerInvalid
                if (owner[Users.suspendedUntil] > now) return@transaction BotCreateResult.OwnerInvalid
                val owned = BotApps.selectAll().where { BotApps.ownerUserId eq ownerUserId }.count()
                if (owned >= maxBots.coerceAtLeast(0).toLong()) return@transaction BotCreateResult.MaxBotsReached
                if (BotApps.selectAll().where { BotApps.username eq u }.firstOrNull() != null) {
                    return@transaction BotCreateResult.UsernameTaken
                }
                // Bot identity is also a Users row so message.senderId FK remains valid.
                Users.insert {
                    it[Users.id] = id
                    it[Users.email] = "$id@bots.maodouchat.local"
                    it[Users.passwordHash] = "!" // unusable password
                    it[Users.name] = n
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
                    it[BotApps.createdAt] = now
                    it[BotApps.updatedAt] = now
                }
                BotCreateResult.Success(
                    BotApps.selectAll().where { BotApps.id eq id }.first().toDto().copy(tokenOnce = token)
                )
            }
        } catch (error: Exception) {
            if (isUniqueViolation(error)) BotCreateResult.UsernameTaken else throw error
        }
    }

    fun regenerateToken(botId: String, ownerUserId: String): BotDto? {
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

    fun setWebhook(botId: String, ownerUserId: String, url: String?): BotDto? {
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


    fun setWebhookByToken(botId: String, url: String?): BotDto? {
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

    fun countPendingUpdates(botId: String, offset: Long = 0): Long = transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction 0L
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction 0L
        BotUpdateInbox.selectAll()
            .where {
                (BotUpdateInbox.botId eq botId) and
                    (BotUpdateInbox.id greater offset.coerceAtLeast(0L))
            }
            .count()
    }


    fun setEnabled(botId: String, ownerUserId: String, enabled: Boolean): BotDto? {
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

    
    fun delete(botId: String, ownerUserId: String): Boolean {
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
            ChatUserSettings.deleteWhere { ChatUserSettings.userId eq botId }
            ChatParticipants.deleteWhere { ChatParticipants.userId eq botId }
            MessageReactions.deleteWhere { MessageReactions.userId eq botId }
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
            ReadReceipts.deleteWhere { ReadReceipts.userId eq botId }
            StarMessages.deleteWhere { StarMessages.userId eq botId }
            SenderKeyDistributions.deleteWhere {
                (SenderKeyDistributions.senderId eq botId) or
                    (SenderKeyDistributions.recipientUserId eq botId)
            }
            AiPreferences.deleteWhere { AiPreferences.userId eq botId }
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
            // Keep a tombstoned Users row for historical message.senderId FK integrity.
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

    fun authenticate(token: String): BotDto? {
        val hash = hashToken(token.trim())
        return transaction {
            val row = BotApps.selectAll().where { (BotApps.tokenHash eq hash) and (BotApps.enabled eq true) }
                .firstOrNull() ?: return@transaction null
            // 8.33 安全修复 HIGH：bot 端点只校验 token 有效，封禁/注销用户的自有 bot 可继续
            // 收发消息（绕过 suspend「禁止发消息/禁止入群交互」）。在认证源头统一校验 owner 状态。
            val owner = Users.selectAll().where { Users.id eq row[BotApps.ownerUserId] }.firstOrNull()
            if (owner == null || owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > System.currentTimeMillis()) {
                return@transaction null
            }
            // 8.37：消息/动态限制同样拦截——被禁发消息/禁发动态的账号不得借 bot 绕过
            // （bot 以自身身份收发，Sockets/REST 对 senderId 的限制查不到 owner 的行）
            val now = System.currentTimeMillis()
            if (owner[Users.messageRestrictedUntil] > now || owner[Users.postRestrictedUntil] > now) {
                return@transaction null
            }
            row.toDto()
        }
    }

    /** Returns the stored token hash for a bot, or null if not found. Used for webhook signing. */
    fun getTokenHash(botId: String): String? = transaction {
        BotApps.selectAll().where { BotApps.id eq botId }
            .firstOrNull()?.get(BotApps.tokenHash)
    }

    /** 8.33：bot 所在全部会话（删除 bot 后需要向这些群广播 memberRevision）。 */
    fun groupChatIdsFor(botId: String): List<String> = transaction {
        ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId eq botId }
            .map { it[ChatParticipants.chatId] }
            .distinct()
    }


    fun enqueueUpdate(botId: String, updateJson: String) {
        if (updateJson.length > MAX_UPDATE_JSON_CHARS) {
            logger.warn("Bot update json too large ({} chars), dropping for {}", updateJson.length, botId)
            return
        }
        transaction {
            val now = System.currentTimeMillis()
            val bot = BotApps.selectAll()
                .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                .forUpdate()
                .firstOrNull() ?: return@transaction
            if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction
            BotUpdateInbox.insert {
                it[BotUpdateInbox.botId] = botId
                it[BotUpdateInbox.updateJson] = updateJson
                it[createdAt] = now
            }
        }
    }

    private fun isOwnerDeliverable(ownerUserId: String, now: Long): Boolean {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.firstOrNull() ?: return false
        return owner[Users.deletedAt] == null &&
            owner[Users.suspendedUntil] <= now &&
            owner[Users.messageRestrictedUntil] <= now &&
            owner[Users.postRestrictedUntil] <= now
    }

    /** Validate callback ownership/membership and enqueue it atomically with the audit row. */
    fun enqueueCallbackIfAuthorized(
        chatId: String,
        userId: String,
        botId: String,
        messageId: String,
        callbackData: String,
        updateJson: String
    ): Boolean = transaction {
        if (!isBoundedId(chatId) || !isBoundedId(userId) || !isBoundedId(botId) || !isBoundedId(messageId)) {
            return@transaction false
        }
        if (callbackData.isBlank() || callbackData.length > MAX_CALLBACK_DATA_LENGTH) return@transaction false
        // 8.48：超限拒写，避免 take 截断产生损坏 JSON 行
        if (updateJson.length > MAX_UPDATE_JSON_CHARS) return@transaction false
        val now = System.currentTimeMillis()
        // Bot deletion uses bot -> chat; lock in the same order before writing bot-owned rows.
        val botRow = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .forUpdate()
            .firstOrNull() ?: return@transaction false
        val owner = Users.selectAll().where { Users.id eq botRow[BotApps.ownerUserId] }.firstOrNull()
        if (owner == null || owner[Users.deletedAt] != null ||
            owner[Users.suspendedUntil] > now ||
            owner[Users.messageRestrictedUntil] > now ||
            owner[Users.postRestrictedUntil] > now
        ) return@transaction false
        Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction false
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        if (participants.none { it[ChatParticipants.userId] == userId } ||
            participants.none { it[ChatParticipants.userId] == botId }
        ) return@transaction false
        val message = Messages.selectAll().where { Messages.id eq messageId }.firstOrNull()
            ?: return@transaction false
        if (message[Messages.chatId] != chatId || message[Messages.senderId] != botId) return@transaction false
        if (!containsCallbackData(message[Messages.content], callbackData)) return@transaction false
        BotUpdateInbox.insert {
            it[BotUpdateInbox.botId] = botId
            it[BotUpdateInbox.updateJson] = updateJson
            it[createdAt] = now
        }
        BotCommandLogs.insert {
            it[id] = "bcl_" + UUID.randomUUID().toString().replace("-", "").take(16)
            it[BotCommandLogs.botId] = botId
            it[BotCommandLogs.chatId] = chatId
            it[BotCommandLogs.userId] = userId
            it[BotCommandLogs.command] = "callback:" + callbackData.take(40)
            it[createdAt] = now
        }
        true
    }

    fun getUpdates(botId: String, offset: Long = 0, limit: Int = 50): List<Pair<Long, String>> = transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction emptyList()
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction emptyList()
        BotUpdateInbox.selectAll()
            .where {
                (BotUpdateInbox.botId eq botId) and
                    (BotUpdateInbox.id greater offset.coerceAtLeast(0L))
            }
            .orderBy(BotUpdateInbox.id to org.jetbrains.exposed.sql.SortOrder.ASC)
            .limit(limit.coerceIn(1, 100))
            .map { it[BotUpdateInbox.id] to it[BotUpdateInbox.updateJson] }
    }

    fun deleteUpdates(botId: String, upToId: Long): Int = transaction {
        val now = System.currentTimeMillis()
        val bot = BotApps.selectAll()
            .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction 0
        if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction 0
        BotUpdateInbox.deleteWhere {
            (BotUpdateInbox.botId eq botId) and (BotUpdateInbox.id lessEq upToId)
        }
    }

    /**
     * 清理超过保留期仍未消费的 bot 更新收件箱（默认 30 天）。
     * 正常 bot 消费后即删；bot 停用/失联时这里兜底，防止积压无限增长。
     * 由 Routing.kt 的周期清理循环调用。
     */
    fun purgeOldInbox(retentionDays: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            BotUpdateInbox.deleteWhere { BotUpdateInbox.createdAt less cutoff }
        }
    }

    fun logCommand(botId: String, chatId: String?, userId: String?, command: String) {
        transaction {
            BotApps.selectAll().where { BotApps.id eq botId }.forUpdate().firstOrNull()
                ?: return@transaction
            BotCommandLogs.insert {
                it[id] = "bcl_" + UUID.randomUUID().toString().replace("-", "").take(16)
                it[BotCommandLogs.botId] = botId
                it[BotCommandLogs.chatId] = chatId
                it[BotCommandLogs.userId] = userId
                it[BotCommandLogs.command] = command.take(120)
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }


    data class CommandLogDto(
        val id: String,
        val botId: String,
        val chatId: String?,
        val userId: String?,
        val command: String,
        val createdAt: Long
    )

    fun listCommandLogs(botId: String, limit: Int = 50, offset: Int = 0): List<CommandLogDto> = transaction {
        BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .orderBy(
                BotCommandLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                BotCommandLogs.id to org.jetbrains.exposed.sql.SortOrder.DESC
            )
            .limit(limit.coerceIn(1, 200), offset.toLong().coerceAtLeast(0))
            .map {
                CommandLogDto(
                    id = it[BotCommandLogs.id],
                    botId = it[BotCommandLogs.botId],
                    chatId = it[BotCommandLogs.chatId],
                    userId = it[BotCommandLogs.userId],
                    command = it[BotCommandLogs.command],
                    createdAt = it[BotCommandLogs.createdAt]
                )
            }
    }


    fun get(botId: String): BotDto? = transaction {
        BotApps.selectAll().where { BotApps.id eq botId }.firstOrNull()?.toDto()
    }

    fun adminList(limit: Int = 50, offset: Int = 0): List<BotDto> = transaction {
        BotApps.selectAll()
            .orderBy(
                BotApps.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                BotApps.id to org.jetbrains.exposed.sql.SortOrder.DESC
            )
            .limit(limit.coerceIn(1, 200), offset.toLong().coerceAtLeast(0))
            .map { it.toDto() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toDto() = BotDto(
        id = this[BotApps.id],
        ownerUserId = this[BotApps.ownerUserId],
        name = this[BotApps.name],
        username = this[BotApps.username],
        description = this[BotApps.description],
        tokenPrefix = this[BotApps.tokenPrefix],
        webhookUrl = this[BotApps.webhookUrl],
        commandsJson = this[BotApps.commandsJson],
        enabled = this[BotApps.enabled],
        createdAt = this[BotApps.createdAt],
        updatedAt = this[BotApps.updatedAt]
    )

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val sqlState = (current as? java.sql.SQLException)?.sqlState
            if (sqlState == "23505") return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("duplicate key") || message.contains("unique constraint")) return true
            current = current.cause
        }
        return false
    }

    private fun normalizeUsername(raw: String): String? {
        val u = raw.trim().lowercase().removePrefix("@")
        if (!Regex("^[a-z][a-z0-9_]{2,31}$").matches(u)) return null
        return u
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return "mdc_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashToken(token: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }


    fun getMyCommands(botId: String): List<BotCommandDef> {
        val bot = get(botId) ?: return emptyList()
        return parseCommands(bot.commandsJson)
    }

    fun setMyCommands(botId: String, commands: List<BotCommandDef>): List<BotCommandDef>? {
        val normalized = normalizeCommands(commands) ?: return null
        val json = encodeCommands(normalized)
        val now = System.currentTimeMillis()
        return transaction {
            val bot = BotApps.selectAll()
                .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                .forUpdate().firstOrNull()
                ?: return@transaction null
            if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
            val updated = BotApps.update({ BotApps.id eq botId }) {
                it[commandsJson] = json
                it[updatedAt] = now
            }
            if (updated == 1) normalized else null
        }
    }

    fun setMyDescription(botId: String, description: String?): BotDto? {
        val clean = description?.trim()?.take(500)
        val now = System.currentTimeMillis()
        return transaction {
            val bot = BotApps.selectAll()
                .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                .forUpdate().firstOrNull()
                ?: return@transaction null
            if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
            val updated = BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.description] = clean
                it[updatedAt] = now
            }
            if (updated != 1) return@transaction null
            BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
        }
    }

    fun setMyName(botId: String, name: String): BotDto? {
        val clean = name.trim().take(120)
        if (clean.isBlank()) return null
        val now = System.currentTimeMillis()
        return transaction {
            val bot = BotApps.selectAll()
                .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                .forUpdate().firstOrNull()
                ?: return@transaction null
            if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction null
            val updated = BotApps.update({ BotApps.id eq botId }) {
                it[BotApps.name] = clean
                it[updatedAt] = now
            }
            if (updated != 1) return@transaction null
            Users.update({ Users.id eq botId }) {
                it[Users.name] = clean
            }
            BotApps.selectAll().where { BotApps.id eq botId }.first().toDto()
        }
    }

    fun clearMyCommands(botId: String): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            val bot = BotApps.selectAll()
                .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                .forUpdate().firstOrNull()
                ?: return@transaction false
            if (!isOwnerDeliverable(bot[BotApps.ownerUserId], now)) return@transaction false
            BotApps.update({ BotApps.id eq botId }) {
                it[commandsJson] = null
                it[updatedAt] = now
            } == 1
        }
    }

    private fun parseCommands(raw: String?): List<BotCommandDef> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val el = json.parseToJsonElement(raw)
            val arr = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { item ->
                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val command = obj["command"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }?.trim().orEmpty()
                val description = obj["description"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }?.trim().orEmpty()
                if (command.isBlank() || description.isBlank()) null
                else BotCommandDef(command = command, description = description)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun normalizeCommands(commands: List<BotCommandDef>): List<BotCommandDef>? {
        if (commands.size > 100) return null
        val out = ArrayList<BotCommandDef>(commands.size)
        val seen = HashSet<String>()
        val cmdRe = Regex("^[a-z][a-z0-9_]{0,31}$")
        for (c in commands) {
            val command = c.command.trim().lowercase().removePrefix("/")
            val description = c.description.trim().take(256)
            if (!cmdRe.matches(command)) return null
            if (description.isBlank()) return null
            if (!seen.add(command)) return null
            out.add(BotCommandDef(command = command, description = description))
        }
        return out
    }

    private fun encodeCommands(commands: List<BotCommandDef>): String {
        val arr = kotlinx.serialization.json.buildJsonArray {
            for (c in commands) {
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("command", kotlinx.serialization.json.JsonPrimitive(c.command))
                        put("description", kotlinx.serialization.json.JsonPrimitive(c.description))
                    }
                )
            }
        }
        return arr.toString()
    }

    private fun containsCallbackData(content: String, callbackData: String): Boolean {
        val open = content.lastIndexOf("<meta>")
        val close = content.lastIndexOf("</meta>")
        if (open < 0 || close <= open || close + 7 != content.length) return false
        val raw = content.substring(open + 6, close)
        if (raw.length > MAX_CALLBACK_META_LENGTH) return false
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return false
        val rows = root["inlineKeyboard"] as? JsonArray ?: return false
        return rows.any { rowElement ->
            val row = rowElement as? JsonArray ?: return@any false
            row.any { buttonElement ->
                val button = buttonElement as? JsonObject ?: return@any false
                (button["callbackData"] as? JsonPrimitive)?.content == callbackData
            }
        }
    }

    private fun isBoundedId(value: String): Boolean =
        value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    internal fun isAllowedWebhookUrl(url: String): Boolean {
        return try {
            val u = java.net.URI(url)
            val scheme = (u.scheme ?: "").lowercase()
            val host = (u.host ?: "").lowercase()
            if (host.isBlank() || u.userInfo != null || u.fragment != null) return false
            when (scheme) {
                "https" -> !isBlockedSsrfHost(host)
                "http" -> allowLoopbackWebhook() && host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Revalidate immediately before every outbound request. DNS failures are blocked. */
    internal fun isWebhookDeliveryAllowed(url: String): Boolean {
        if (!isAllowedWebhookUrl(url)) return false
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            when {
                scheme == "http" -> allowLoopbackWebhook() && host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
                scheme == "https" -> host.isNotBlank() && !resolvesToBlockedIp(host)
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun allowLoopbackWebhook(): Boolean =
        System.getenv("BOT_WEBHOOK_ALLOW_LOOPBACK")?.trim()?.equals("true", ignoreCase = true) == true

    private const val MAX_CALLBACK_DATA_LENGTH = 128
    private const val MAX_CALLBACK_META_LENGTH = 16_000

    private fun isBlockedSsrfHost(host: String): Boolean {
        if (host in setOf("metadata.google.internal", "169.254.169.254")) return true
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        }
        val h = host.removeSurrounding("[", "]").lowercase()
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.startsWith("fc") || h.startsWith("fd")) return true
        if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return true
        return false
    }

    /**
     * DNS 解析后校验每个 IP 是否落入 SSRF 封禁段。
     * 仅在发送时（已处于 Dispatchers.IO）调用，作为主机名字符串校验之外的纵深防御，
     * 防止域名注册后通过 DNS rebinding 解析到内网/元数据地址。
     * 解析失败按 fail-closed 处理（视为封禁），避免向无法解析的主机发起请求。
     */
    internal fun resolvesToBlockedIp(host: String): Boolean {
        return try {
            java.net.InetAddress.getAllByName(host).any { addr ->
                addr.isLoopbackAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isAnyLocalAddress ||
                    addr.isMulticastAddress ||
                    isBlockedSsrfHost(addr.hostAddress ?: "")
            }
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 清理超过保留期的 bot 命令日志（默认 180 天），防止长期累积。
     * 由 Routing.kt 的周期清理循环调用；删 bot/删群时已按 botId/chatId 清理，
     * 这里兜底按时间清理孤立与历史行。
     */
    fun purgeOldCommandLogs(retentionDays: Int = 180): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            BotCommandLogs.deleteWhere { BotCommandLogs.createdAt less cutoff }
        }
    }
}
