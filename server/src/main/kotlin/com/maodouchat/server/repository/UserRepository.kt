package com.maodouchat.server.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.UserPrivacyResponse
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class UserRepository {

    fun createDefaultUsers() {
        val defaults = listOf(
            Triple("u1", "陈亚历克斯", "alex@example.com"),
            Triple("u2", "Alice Chen", "alice@example.com"),
            Triple("u3", "Bob Smith", "bob@example.com"),
            Triple("u4", "Elena Rodriguez", "elena@example.com"),
            Triple("u5", "Sarah Jenkins", "sarah@example.com"),
            Triple("u6", "Arthur Morgan", "arthur@example.com"),
            Triple("u7", "张三", "zhangsan@example.com"),
            Triple("u8", "李四", "lisi@example.com"),
            Triple("u9", "王五", "wangwu@example.com"),
            Triple("u10", "David Kim", "david@example.com"),
            Triple("u11", "Emma Watson", "emma@example.com"),
            Triple("u12", "赵六", "zhaoliu@example.com"),
            Triple("u13", "孙七", "sunqi@example.com"),
        )
        defaults.forEach { (id, name, email) ->
            try {
                transaction {
                    if (Users.selectAll().where { Users.id eq id }.empty()) {
                        Users.insert {
                            it[Users.id] = id
                            it[Users.name] = name
                            it[Users.email] = email
                            it[passwordHash] = BCrypt.withDefaults().hashToString(12, "password123".toCharArray())
                            it[isOnline] = false
                            it[status] = "在线"
                            it[showOnline] = true
                            it[searchable] = true
                            it[defaultPostVisibility] = "PUBLIC"
                            it[isModerator] = email.normalizedEmail() in ServerConfig.moderatorEmails
                        }
                    }
                }
            } catch (error: Exception) {
                if (!isUniqueViolation(error)) throw error
            }
        }
    }

    fun register(name: String, email: String, password: String): UserResponse? {
        val normalizedEmail = email.normalizedEmail()
        val safeName = name.trim().take(MAX_NAME_LENGTH)
        return try {
            transaction {
                if (!Users.selectAll().where { Users.email eq normalizedEmail }.empty()) return@transaction null

                // 完整 UUID，避免 take(8)（32-bit 熵）碰撞导致主键唯一冲突被 isUniqueViolation 吞掉、
                // 误报"邮箱已存在"而注册静默失败。
                val id = "u_${UUID.randomUUID()}"
                val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = safeName
                    it[Users.email] = normalizedEmail
                    it[passwordHash] = hash
                    it[isOnline] = false
                    it[status] = "在线"
                    it[showOnline] = true
                    it[searchable] = true
                    it[defaultPostVisibility] = "PUBLIC"
                    it[isModerator] = normalizedEmail in ServerConfig.moderatorEmails
                }
                // 一次性引导：BOOTSTRAP_FIRST_USER_AS_ADMIN=true 时第一个注册账号自动成为
                // 主管理员（在事务内计数，配合唯一约束保证并发下只有一个"第一个"用户）。
                if (ServerConfig.bootstrapFirstUserAsAdmin &&
                    Users.selectAll().where { Users.id neq id }.empty()
                ) {
                    com.maodouchat.server.config.AdminAccess.grantAdmin(id)
                }
                UserResponse(id, safeName, normalizedEmail, status = "在线", isModerator = normalizedEmail in ServerConfig.moderatorEmails)
            }
        } catch (error: Exception) {
            if (isUniqueViolation(error)) null else throw error
        }
    }

    fun login(email: String, password: String): UserResponse? {
        val result = loginWithFactors(email, password, totpCode = null)
        // Legacy path: only succeed when TOTP is not enabled.
        return if (result.passwordOk && !result.totpEnabled) result.user else null
    }

    
    data class LoginResult(
        val user: UserResponse?,
        val passwordOk: Boolean,
        val totpEnabled: Boolean,
        val totpOk: Boolean
    )

    fun loginWithFactors(email: String, password: String, totpCode: String?): LoginResult {
        return transaction {
            val normalizedEmail = email.normalizedEmail()
            val user = Users.selectAll().where { Users.email eq normalizedEmail }.forUpdate().firstOrNull()
                ?: return@transaction LoginResult(null, false, false, false)
            if (user[Users.deletedAt] != null) return@transaction LoginResult(null, false, false, false)
            if (user[Users.suspendedUntil] > System.currentTimeMillis()) {
                return@transaction LoginResult(null, false, false, false)
            }
            val hash = user[Users.passwordHash]
            val passwordOk = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
            if (!passwordOk) return@transaction LoginResult(null, false, false, false)
            val enabled = user[Users.totpEnabled] && !user[Users.totpSecret].isNullOrBlank()
            val secret = user[Users.totpSecret].orEmpty()
            val totpOk = if (!enabled) true else {
                val totpAccepted = com.maodouchat.server.service.TotpService.verify(secret, totpCode.orEmpty()) { candidate ->
                    acceptTotpCounter(user, candidate)
                }
                // 0.75：TOTP 校验失败时尝试恢复码（单次使用；丢失验证器可恢复登录）
                if (totpAccepted) true else consumeBackupCode(user, totpCode.orEmpty())
            }
            if (passwordOk && totpOk) {
                Users.update({ Users.email eq normalizedEmail }) {
                    it[lastSeen] = System.currentTimeMillis()
                }
            }
            LoginResult(
                user = if (passwordOk && totpOk) user.toPrivateUser(isOnlineOverride = false) else null,
                passwordOk = true,
                totpEnabled = enabled,
                totpOk = totpOk
            )
        }
    }

    fun isTotpEnabled(userId: String): Boolean = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction false
        row[Users.totpEnabled] && !row[Users.totpSecret].isNullOrBlank()
    }

    fun beginTotpSetup(userId: String): Pair<String, String>? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction null
        if (row[Users.deletedAt] != null) return@transaction null
        val secret = com.maodouchat.server.service.TotpService.generateSecret()
        Users.update({ Users.id eq userId }) {
            it[totpSecret] = secret
            it[totpEnabled] = false
        }
        val email = row[Users.email]
        val uri = com.maodouchat.server.service.TotpService.provisioningUri(secret, email)
        secret to uri
    }

    fun confirmTotpSetup(userId: String, code: String): List<String>? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction null
        val secret = row[Users.totpSecret] ?: return@transaction null
        if (!com.maodouchat.server.service.TotpService.verify(secret, code) { candidate ->
                acceptTotpCounter(row, candidate)
            }
        ) return@transaction null
        // 0.75：生成 8 个恢复码（明文仅本次返回，落库存 BCrypt 哈希，单次使用）
        val codes = (1..8).map { generateBackupCode() }
        val hashes = codes.map { code -> BCrypt.withDefaults().hashToString(10, code.toCharArray()) }
        Users.update({ Users.id eq userId }) {
            it[totpEnabled] = true
            it[Users.totpBackupCodes] = hashes.joinToString(",")
        }
        codes
    }

    fun disableTotp(userId: String, code: String): Boolean = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction false
        val secret = row[Users.totpSecret]
        val enabled = row[Users.totpEnabled]
        if (enabled && !secret.isNullOrBlank()) {
            // 8.63 修复：关闭 TOTP 是「已认证（持有效 token）+ 验证码有时限（30s±1）」的双因子确认，
            // 不应受登录防重放计数器（acceptTotpCounter）约束——否则刚登录后无法立即关闭 TOTP
            //（登录已把 totpLastCounter 推进，当前窗口内任何新验证码都会被当作重放拒绝）。
            // 仅校验验证码在当前时限窗口内有效即可。
            if (!com.maodouchat.server.service.TotpService.verify(secret, code, trackReplay = false) { true }
            ) return@transaction false
        }
        Users.update({ Users.id eq userId }) {
            it[totpSecret] = null
            it[totpEnabled] = false
            it[Users.totpBackupCodes] = null
        }
        true
    }

    /** 0.77：验证当前 TOTP 后重新生成恢复码（旧码全部作废）。返回新恢复码明文，失败返回 null。 */
    fun regenerateBackupCodes(userId: String, code: String): List<String>? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction null
        if (!row[Users.totpEnabled]) return@transaction null
        val secret = row[Users.totpSecret] ?: return@transaction null
        if (!com.maodouchat.server.service.TotpService.verify(secret, code) { candidate ->
                acceptTotpCounter(row, candidate)
            }
        ) return@transaction null
        val codes = (1..8).map { generateBackupCode() }
        val hashes = codes.map { c -> BCrypt.withDefaults().hashToString(10, c.toCharArray()) }
        Users.update({ Users.id eq userId }) {
            it[Users.totpBackupCodes] = hashes.joinToString(",")
        }
        codes
    }

    /**
     * 8.51 修复 M2：TOTP counter DB 原子 CAS——仅当候选 counter 严格大于已持久化值才接受并落库。
     * 调用方须在同一事务内持该用户行锁（forUpdate），杜绝重启/多实例后重放同一步 code。
     */
    private fun acceptTotpCounter(row: org.jetbrains.exposed.sql.ResultRow, candidate: Long): Boolean {
        val persisted = row[Users.totpLastCounter]
        if (candidate <= persisted) return false
        Users.update({ Users.id eq row[Users.id] }) {
            it[Users.totpLastCounter] = candidate
        }
        return true
    }

    /** 0.75：校验并单次消费恢复码（匹配即删除该码）。调用方须持用户行锁在同一事务内。 */
    private fun consumeBackupCode(row: org.jetbrains.exposed.sql.ResultRow, code: String): Boolean {
        val raw = row[Users.totpBackupCodes] ?: return false
        if (raw.isBlank()) return false
        val hashes = raw.split(',')
        val normalized = code.trim()
        val idx = hashes.indexOfFirst { hash ->
            hash.isNotBlank() && BCrypt.verifyer().verify(normalized.toCharArray(), hash).verified
        }
        if (idx < 0) return false
        val remaining = hashes.filterIndexed { i, _ -> i != idx }.filter { it.isNotBlank() }
        Users.update({ Users.id eq row[Users.id] }) {
            it[Users.totpBackupCodes] = if (remaining.isEmpty()) null else remaining.joinToString(",")
        }
        return true
    }

    /** 0.75：生成 8 位数字恢复码（SecureRandom，杜绝可预测序列）。 */
    private fun generateBackupCode(): String {
        val rand = java.security.SecureRandom()
        val sb = StringBuilder(8)
        repeat(8) { sb.append('0' + rand.nextInt(10)) }
        return sb.toString()
    }


    fun getById(userId: String): UserResponse? {
        return transaction {
            Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null }
                ?.toPrivateUser()
        }
    }

    /** 当前用户公开形态（含 lastSeen 精确值，8.32 一致性：/api/users/me/public 不再返回私有字段）。 */
    fun getPublicMe(userId: String): UserResponse? {
        return transaction {
            Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null }
                ?.toPublicUser(lastSeenVisible = true)
        }
    }

    /**
     * 查看用户公开资料。viewer 与 target 双向拉黑时返回 null（404）。
     * lastSeen 仅在 viewer 与 target 存在 1:1 会话时返回（8.30 隐私修复）。
     */
    fun getPublicById(userId: String, viewerId: String? = null): UserResponse? {
        return transaction {
            val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
            if (viewerId != null && viewerId != userId && userId in blockedUserIdsInTx(viewerId)) {
                return@transaction null
            }
            val lastSeenVisible = viewerId != null && viewerId != userId && hasDirectChatInTx(viewerId, userId)
            row.toPublicUser(lastSeenVisible = lastSeenVisible)
        }
    }

    /** 可搜索用户目录（过滤双向拉黑，8.30 隐私修复）。 */
    fun getAll(limit: Int = 100, offset: Int = 0, viewerId: String? = null): List<UserResponse> {
        return transaction {
            val blocked = viewerId?.let { blockedUserIdsInTx(it) } ?: emptySet()
            val base = Users.selectAll().where { Users.searchable eq true }
            val query = if (blocked.isEmpty()) base else base.andWhere { Users.id notInList blocked }
            query.orderBy(Users.id to SortOrder.ASC)
                .limit(limit.coerceIn(1, 500), offset.coerceAtLeast(0).toLong())
                .map { it.toPublicUser() }
        }
    }

    /**
     * 搜索用户（大小写不敏感的 LIKE；过滤掉不可被搜索的用户；过滤双向拉黑）
     * @param excludeUserId 排除指定用户（通常是搜索发起者自己）
     */
    fun searchUsers(keyword: String, excludeUserId: String? = null, limit: Int = 30, viewerId: String? = null): List<UserResponse> {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return emptyList()
        return transaction {
            val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%${escaped.lowercase()}%"
            val baseQuery = Users.selectAll().where {
                (Users.searchable eq true) and
                    ((Users.name.lowerCase() like pattern) or (Users.id.lowerCase() like pattern) or (Users.email.lowerCase() like pattern) or (Users.username.lowerCase() like pattern))
            }
            val finalQuery = if (excludeUserId != null) baseQuery.andWhere { Users.id neq excludeUserId } else baseQuery
            val blocked = viewerId?.let { blockedUserIdsInTx(it) } ?: emptySet()
            val query = if (blocked.isEmpty()) finalQuery else finalQuery.andWhere { Users.id notInList blocked }
            query.orderBy(Users.name to SortOrder.ASC)
                .limit(limit.coerceIn(1, 100))
                .map { it.toPublicUser() }
        }
    }

    /**
     * 检查用户是否允许广播在线状态（showOnline 隐私设置）
     */
    fun shouldBroadcastOnline(userId: String): Boolean {
        return transaction {
            Users.selectAll().where { Users.id eq userId }
                .firstOrNull()?.get(Users.showOnline) ?: false
        }
    }

    fun setOnline(userId: String, online: Boolean) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[isOnline] = online
                it[lastSeen] = System.currentTimeMillis()
            }
        }
    }

    fun getByEmail(email: String): UserResponse? {
        return transaction {
            Users.selectAll().where { Users.email eq email.normalizedEmail() }.firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null }
                ?.toPrivateUser()
        }
    }

    fun isModerator(userId: String): Boolean {
        return transaction {
            Users.selectAll().where { Users.id eq userId }
                .firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null }
                ?.get(Users.isModerator) == true
        }
    }

    fun getMessageRestrictionUntil(userId: String): Long = getRestrictionUntil(userId, Restriction.MESSAGE)

    fun getPostRestrictionUntil(userId: String): Long = getRestrictionUntil(userId, Restriction.POST)

    fun getSuspendedUntil(userId: String): Long = getRestrictionUntil(userId, Restriction.SUSPEND)

    fun applyModerationRestriction(userId: String, action: String): Long? {
        val normalizedAction = action.trim().uppercase()
        val duration = when (normalizedAction) {
            "RESTRICT_MESSAGES_24H" -> 24L * 60L * 60L * 1000L
            "RESTRICT_POSTS_7D" -> 7L * 24L * 60L * 60L * 1000L
            "SUSPEND_24H" -> 24L * 60L * 60L * 1000L
            else -> return null
        }
        val now = System.currentTimeMillis()
        val until = now + duration
        return transaction {
            val row = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null } ?: return@transaction null
            Users.update({ Users.id eq userId }) {
                when (normalizedAction) {
                    "RESTRICT_MESSAGES_24H" -> it[Users.messageRestrictedUntil] = maxOf(row[Users.messageRestrictedUntil], until)
                    "RESTRICT_POSTS_7D" -> it[Users.postRestrictedUntil] = maxOf(row[Users.postRestrictedUntil], until)
                    "SUSPEND_24H" -> it[Users.suspendedUntil] = maxOf(row[Users.suspendedUntil], until)
                }
            }
            until
        }
    }

    fun updateProfile(userId: String, name: String? = null, status: String? = null) {
        transaction {
            Users.update({ Users.id eq userId }) {
                name?.trim()?.takeIf { it.isNotBlank() }?.let { value -> it[Users.name] = value.take(MAX_NAME_LENGTH) }
                status?.trim()?.let { value -> it[Users.status] = value.take(MAX_STATUS_LENGTH) }
            }
        }
    }

    fun replaceAvatar(userId: String, avatarUrl: String?): AvatarReplacementResult? = transaction {
        val normalized = avatarUrl?.trim()
        // 8.37：非法头像地址返回 null（由路由层 400），不得 require 抛 IllegalArgumentException 变 500
        if (!(normalized == null || isValidAvatarUrl(normalized))) return@transaction null
        val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction null
        if (row[Users.deletedAt] != null) return@transaction null
        val previousUrl = row[Users.avatar]
        if (previousUrl != normalized) {
            Users.update({ Users.id eq userId }) { it[avatar] = normalized }
        }
        AvatarReplacementResult(previousUrl = previousUrl, currentUrl = normalized)
    }

    fun isCurrentAvatarUrl(avatarUrl: String): Boolean = transaction {
        !Users.selectAll().where { (Users.avatar eq avatarUrl) and Users.deletedAt.isNull() }.empty()
    }

    private fun getRestrictionUntil(userId: String, restriction: Restriction): Long {
        return transaction {
            val row = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null } ?: return@transaction 0L
            when (restriction) {
                Restriction.MESSAGE -> row[Users.messageRestrictedUntil]
                Restriction.POST -> row[Users.postRestrictedUntil]
                Restriction.SUSPEND -> row[Users.suspendedUntil]
            }.takeIf { it > System.currentTimeMillis() } ?: 0L
        }
    }

    /**
     * 修改密码：先校验旧密码，成功后用 BCrypt cost=12 重哈希
     * @return true 改密成功；false 用户不存在或旧密码错误
     */
    fun changePassword(userId: String, oldPassword: String, newPassword: String): Boolean {
        return transaction {
            val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull() ?: return@transaction false
            if (row[Users.deletedAt] != null) return@transaction false
            if (!BCrypt.verifyer().verify(oldPassword.toCharArray(), row[Users.passwordHash]).verified) return@transaction false
            val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
            Users.update({ Users.id eq userId }) { it[passwordHash] = newHash }
            true
        }
    }

    /**
     * 邮箱验证码重置密码（调用方已校验验证码）。
     * @return 成功时返回 userId；邮箱不存在或已注销时返回 null（路由应对外统一文案，防枚举）
     */
    fun resetPasswordByEmail(email: String, newPassword: String): String? {
        return transaction {
            val normalizedEmail = email.normalizedEmail()
            val row = Users.selectAll().where { Users.email eq normalizedEmail }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (row[Users.deletedAt] != null) return@transaction null
            val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
            Users.update({ Users.id eq row[Users.id] }) { it[passwordHash] = newHash }
            row[Users.id]
        }
    }

    fun verifyPassword(userId: String, password: String): Boolean = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction false
        if (row[Users.deletedAt] != null || row[Users.suspendedUntil] > System.currentTimeMillis()) return@transaction false
        BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash]).verified
    }

    fun deleteAccount(userId: String, password: String): AccountDeactivationResult? =
        deactivateAccount(userId, authorized = { row ->
            BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash]).verified
        })

    fun adminDeactivateAccount(userId: String, actorId: String): AccountDeactivationResult? = deactivateAccount(
        userId = userId,
        authorized = { true },
        onDeactivated = { deletedAt ->
            ModerationAuditLog.insert {
                it[ModerationAuditLog.userId] = userId
                it[action] = "ADMIN_ACCOUNT_DEACTIVATED"
                it[detail] = "deletedAt=$deletedAt"
                it[ModerationAuditLog.actorId] = actorId
                it[createdAt] = deletedAt
            }
        }
    )

    private fun deactivateAccount(
        userId: String,
        authorized: (ResultRow) -> Boolean,
        onDeactivated: (Long) -> Unit = {}
    ): AccountDeactivationResult? {
        return transaction {
            val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (row[Users.deletedAt] != null) return@transaction null
            if (!authorized(row)) return@transaction null

            val now = System.currentTimeMillis()
            val avatarUrl = row[Users.avatar]
            val deletedEmail = "deleted_${userId}_${now}@deleted.maodouchat.local"
            val randomPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also(secureRandom::nextBytes))
            val deletedHash = BCrypt.withDefaults().hashToString(12, randomPassword.toCharArray())

            // 与 BotRepository.delete 保持 owner -> bot -> chat 锁序。
            val orphanedBotAttachmentIds = removeOwnedBots(userId, now)
            // 再处理群主转让与退群；空会话级联删除时收集附件 ID，供调用方清磁盘。
            val orphanedAttachmentIds = (orphanedBotAttachmentIds + removeUserFromAllChats(userId, now)).distinct()

            BlockedUsers.deleteWhere { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            StarMessages.deleteWhere { StarMessages.userId eq userId }
            ReadReceipts.deleteWhere { ReadReceipts.userId eq userId }
            MessageReactions.deleteWhere { MessageReactions.userId eq userId }
            RefreshTokens.deleteWhere { RefreshTokens.userId eq userId }
            AuthSessions.deleteWhere { AuthSessions.userId eq userId }
            RevokedAccessTokens.deleteWhere { RevokedAccessTokens.userId eq userId }
            AiPreferences.deleteWhere { AiPreferences.userId eq userId }
            AiAuditLogs.deleteWhere { AiAuditLogs.userId eq userId }
            // Moderation audit records are intentionally retained. Account deactivation must not
            // erase the administrative history associated with the target account.
            AiSummarySyncEnvelopes.deleteWhere { AiSummarySyncEnvelopes.userId eq userId }
            NotificationPreferences.deleteWhere { NotificationPreferences.userId eq userId }
            ClientPrefs.deleteWhere { ClientPrefs.userId eq userId }
            ChatFolders.deleteWhere { ChatFolders.userId eq userId }
            AnnouncementAcks.deleteWhere { AnnouncementAcks.userId eq userId }
            UserTagAssignments.deleteWhere { UserTagAssignments.userId eq userId }
            DeviceEventSequences.deleteWhere { DeviceEventSequences.userId eq userId }
            DeviceEventConsistencyLog.deleteWhere { DeviceEventConsistencyLog.userId eq userId }
            FriendRequests.deleteWhere {
                (FriendRequests.fromUserId eq userId) or (FriendRequests.toUserId eq userId)
            }
            Friendships.deleteWhere {
                (Friendships.userLowId eq userId) or (Friendships.userHighId eq userId)
            }
            PushTokens.deleteWhere { PushTokens.userId eq userId }
            UserLocations.deleteWhere { UserLocations.userId eq userId }
            SenderKeyDistributions.deleteWhere { (SenderKeyDistributions.senderId eq userId) or (SenderKeyDistributions.recipientUserId eq userId) }
            SignalKeys.deleteWhere { SignalKeys.userId eq userId }
            SignalDevices.deleteWhere { SignalDevices.userId eq userId }
            SignalingMessages.deleteWhere { (SignalingMessages.fromUserId eq userId) or (SignalingMessages.toUserId eq userId) }
            PostLikes.deleteWhere { PostLikes.userId eq userId }
            PostComments.deleteWhere { PostComments.authorId eq userId }
            CommentLikes.deleteWhere { CommentLikes.userId eq userId }
            val authoredPostIds = Posts.selectAll().where { Posts.authorId eq userId }.map { it[Posts.id] }
            if (authoredPostIds.isNotEmpty()) {
                PostLikes.deleteWhere { PostLikes.postId inList authoredPostIds }
                PostComments.deleteWhere { PostComments.postId inList authoredPostIds }
                PostImageClaims.deleteWhere { PostImageClaims.postId inList authoredPostIds }
            }
            Posts.deleteWhere { Posts.authorId eq userId }
            deletePollsCreatedBy(listOf(userId))
            deleteGroupPlayCreatedBy(listOf(userId))
            GroupPollVotes.deleteWhere { GroupPollVotes.userId eq userId }
            GroupCheckins.deleteWhere { GroupCheckins.userId eq userId }
            GroupChainEntries.deleteWhere { GroupChainEntries.userId eq userId }
            GroupPkVotes.deleteWhere { GroupPkVotes.userId eq userId }
            // 用户自己调用过的机器人命令日志也清除
            BotCommandLogs.deleteWhere { BotCommandLogs.userId eq userId }

            Users.update({ Users.id eq userId }) {
                it[Users.name] = DELETED_USER_NAME
                it[Users.email] = deletedEmail
                it[passwordHash] = deletedHash
                it[avatar] = null
                it[status] = "账号已注销"
                it[isOnline] = false
                it[showOnline] = false
                it[showStatus] = false
                it[searchable] = false
                it[defaultPostVisibility] = "PRIVATE"
                it[accessTokenVersion] = row[Users.accessTokenVersion] + 1
                it[deletedAt] = now
                it[lastSeen] = now
            }
            onDeactivated(now)
            AccountDeactivationResult(
                deletedAt = now,
                orphanedAttachmentIds = orphanedAttachmentIds,
                avatarUrl = avatarUrl
            )
        }
    }

    /** Must run after locking the owner Users row. */
    private fun removeOwnedBots(ownerUserId: String, now: Long): List<String> {
        val ownedBots = BotApps.selectAll()
            .where { BotApps.ownerUserId eq ownerUserId }
            .orderBy(BotApps.id to SortOrder.ASC)
            .forUpdate()
            .toList()
        if (ownedBots.isEmpty()) return emptyList()
        val botIds = ownedBots.map { it[BotApps.id] }
        val botIdSet = botIds.toSet()
        val chatIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId inList botIds }
            .map { it[ChatParticipants.chatId] }
            .distinct()
            .sorted()
        val lockedChats: List<ResultRow> = if (chatIds.isEmpty()) emptyList() else Chats.selectAll()
            .where { Chats.id inList chatIds }
            .orderBy(Chats.id to SortOrder.ASC)
            .forUpdate()
            .toList()

        lockedChats.forEach { chat ->
            if (!chat[Chats.isGroup]) return@forEach
            val chatId = chat[Chats.id]
            val participants = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .toList()
            val deletingOwner = participants.any {
                it[ChatParticipants.userId] in botIdSet && it[ChatParticipants.role] == "OWNER"
            }
            if (deletingOwner) {
                val successor = participants
                    .filterNot { it[ChatParticipants.userId] in botIdSet }
                    .sortedWith(
                        compareByDescending<ResultRow> {
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
                        it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
                        it[GroupAuditLogs.chatId] = chatId
                        it[GroupAuditLogs.actorId] = ownerUserId
                        it[GroupAuditLogs.action] = "OWNERSHIP_TRANSFERRED"
                        it[GroupAuditLogs.targetUserId] = successorId
                        it[GroupAuditLogs.createdAt] = now
                    }
                }
            }
        }

        ChatUserSettings.deleteWhere { ChatUserSettings.userId inList botIds }
        ChatParticipants.deleteWhere { ChatParticipants.userId inList botIds }
        val orphanedAttachmentIds = mutableListOf<String>()
        lockedChats.forEach { chat ->
            val chatId = chat[Chats.id]
            val remaining = ChatParticipants.select(ChatParticipants.userId)
                .where { ChatParticipants.chatId eq chatId }
                .limit(1)
                .any()
            if (!remaining) {
                orphanedAttachmentIds += tearDownEmptyChat(chatId)
            } else if (chat[Chats.isGroup]) {
                Chats.update({ Chats.id eq chatId }) {
                    it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
                }
            } else {
                DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
            }
        }

        deletePollsCreatedBy(botIds)
        GroupPollVotes.deleteWhere { GroupPollVotes.userId inList botIds }
        StarMessages.deleteWhere { StarMessages.userId inList botIds }
        ReadReceipts.deleteWhere { ReadReceipts.userId inList botIds }
        MessageReactions.deleteWhere { MessageReactions.userId inList botIds }
        SenderKeyDistributions.deleteWhere {
            (SenderKeyDistributions.senderId inList botIds) or
                (SenderKeyDistributions.recipientUserId inList botIds)
        }
        AiPreferences.deleteWhere { AiPreferences.userId inList botIds }
        botIds.forEach { botId ->
            BotCommandLogs.deleteWhere {
                (BotCommandLogs.botId eq botId) or (BotCommandLogs.userId eq botId)
            }
        }
        BotUpdateInbox.deleteWhere { BotUpdateInbox.botId inList botIds }
        BotApps.deleteWhere {
            (BotApps.ownerUserId eq ownerUserId) and (BotApps.id inList botIds)
        }
        Users.update({ Users.id inList botIds }) {
            it[Users.name] = "deleted-bot"
            it[Users.status] = ""
            it[Users.avatar] = null
            it[Users.isOnline] = false
            it[Users.showOnline] = false
            it[Users.showStatus] = false
            it[Users.searchable] = false
            it[Users.deletedAt] = now
            it[Users.lastSeen] = 0L
        }
        return orphanedAttachmentIds
    }

    private fun deletePollsCreatedBy(creatorIds: List<String>) {
        if (creatorIds.isEmpty()) return
        val pollIds = GroupPolls.select(GroupPolls.id)
            .where { GroupPolls.creatorId inList creatorIds }
            .orderBy(GroupPolls.id to SortOrder.ASC)
            .forUpdate()
            .map { it[GroupPolls.id] }
        if (pollIds.isEmpty()) return
        GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
        GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
    }

    private fun deleteGroupPlayCreatedBy(creatorIds: List<String>) {
        if (creatorIds.isEmpty()) return
        val chainIds = GroupChains.select(GroupChains.id)
            .where { GroupChains.creatorId inList creatorIds }
            .map { it[GroupChains.id] }
        if (chainIds.isNotEmpty()) {
            GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
            GroupChains.deleteWhere { GroupChains.id inList chainIds }
        }
        val pkIds = GroupPkRounds.select(GroupPkRounds.id)
            .where { GroupPkRounds.creatorId inList creatorIds }
            .map { it[GroupPkRounds.id] }
        if (pkIds.isNotEmpty()) {
            GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
            GroupPkRounds.deleteWhere { GroupPkRounds.id inList pkIds }
        }
    }

    /**
     * On account deactivation: transfer group ownership when needed, remove membership,
     * and tear down empty chats. Returns attachment IDs removed with empty chats so callers
     * can delete disk objects; remaining uploader-owned attachments use deleteForUploader.
     */
    private fun removeUserFromAllChats(userId: String, now: Long): List<String> {
        val orphanedAttachmentIds = mutableListOf<String>()
        val memberships = ChatParticipants.selectAll()
            .where { ChatParticipants.userId eq userId }
            .orderBy(ChatParticipants.chatId to SortOrder.ASC)
            .map { it[ChatParticipants.chatId] to it[ChatParticipants.role] }
        memberships.forEach { (chatId, role) ->
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull() ?: return@forEach
            val others = ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId neq userId) }
                .toList()
            if (chat[Chats.isGroup] && role == "OWNER" && others.isNotEmpty()) {
                val successor = others
                    .sortedWith(
                        compareByDescending<ResultRow> {
                            when (it[ChatParticipants.role]) {
                                "ADMIN" -> 2
                                "MEMBER" -> 1
                                else -> 0
                            }
                        }.thenBy { it[ChatParticipants.joinedAt] }
                    )
                    .first()
                val successorId = successor[ChatParticipants.userId]
                ChatParticipants.update({
                    (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq successorId)
                }) { it[ChatParticipants.role] = "OWNER" }
                GroupAuditLogs.insert {
                    it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
                    it[GroupAuditLogs.chatId] = chatId
                    it[GroupAuditLogs.actorId] = userId
                    it[GroupAuditLogs.action] = "OWNERSHIP_TRANSFERRED"
                    it[GroupAuditLogs.targetUserId] = successorId
                    it[GroupAuditLogs.createdAt] = now
                }
            }
            ChatUserSettings.deleteWhere {
                (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId)
            }
            ChatParticipants.deleteWhere {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }
            if (others.isEmpty()) {
                orphanedAttachmentIds += tearDownEmptyChat(chatId)
            } else {
                // 1:1 注销后 pair 映射失效，避免对方 getOrCreate 回落到半空 chat
                if (!chat[Chats.isGroup]) {
                    DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
                }
                if (chat[Chats.isGroup]) {
                    val latest = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
                    if (latest != null) {
                        Chats.update({ Chats.id eq chatId }) {
                            it[Chats.memberRevision] = latest[Chats.memberRevision] + 1
                        }
                    }
                    GroupAuditLogs.insert {
                        it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
                        it[GroupAuditLogs.chatId] = chatId
                        it[GroupAuditLogs.actorId] = userId
                        it[GroupAuditLogs.action] = "MEMBER_LEFT"
                        it[GroupAuditLogs.targetUserId] = userId
                        it[GroupAuditLogs.createdAt] = now
                    }
                }
            }
        }
        return orphanedAttachmentIds
    }

    /** Deletes all chat-owned rows; returns attachment IDs that still need file cleanup. */
    private fun tearDownEmptyChat(chatId: String): List<String> {
        val messageIds = Messages.select(Messages.id)
            .where { Messages.chatId eq chatId }
            .orderBy(Messages.id to SortOrder.ASC)
            .forUpdate()
            .map { it[Messages.id] }
        if (messageIds.isNotEmpty()) {
            MessageReactions.deleteWhere { MessageReactions.messageId inList messageIds }
            ReadReceipts.deleteWhere { ReadReceipts.messageId inList messageIds }
            StarMessages.deleteWhere { StarMessages.messageId inList messageIds }
            // BUG-1 fix: PinnedMessages FK 到 Messages/Chats，必须先删除
            PinnedMessages.deleteWhere { PinnedMessages.messageId inList messageIds }
        }
        // The caller holds chat; keep the global chat -> message -> attachment lock order.
        Messages.deleteWhere { Messages.chatId eq chatId }
        val attachmentIds = EncryptedAttachments
            .select(EncryptedAttachments.id)
            .where { EncryptedAttachments.chatId eq chatId }
            .orderBy(EncryptedAttachments.id to SortOrder.ASC)
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (attachmentIds.isNotEmpty()) {
            EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
        }
        val pollIds = GroupPolls.select(GroupPolls.id)
            .where { GroupPolls.chatId eq chatId }
            .orderBy(GroupPolls.id to SortOrder.ASC)
            .forUpdate()
            .map { it[GroupPolls.id] }
        if (pollIds.isNotEmpty()) {
            GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
            GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
        }
        // 8.50 修复 L5：对齐 ChatRepository.deleteChatRows 的完整清理集——补群接龙/PK/签到
        // 残留（此前最后成员注销/清空群时留下群玩法孤儿行）
        GroupCheckins.deleteWhere { GroupCheckins.chatId eq chatId }
        val chainIds = GroupChains.select(GroupChains.id)
            .where { GroupChains.chatId eq chatId }
            .map { it[GroupChains.id] }
        if (chainIds.isNotEmpty()) {
            GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
        }
        GroupChains.deleteWhere { GroupChains.chatId eq chatId }
        val pkIds = GroupPkRounds.select(GroupPkRounds.id)
            .where { GroupPkRounds.chatId eq chatId }
            .map { it[GroupPkRounds.id] }
        if (pkIds.isNotEmpty()) {
            GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
        }
        GroupPkRounds.deleteWhere { GroupPkRounds.chatId eq chatId }
        BotCommandLogs.deleteWhere { BotCommandLogs.chatId eq chatId }
        // FK: direct_chat_pairs.chat_id / message_mutations.chat_id → chats.id
        DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
        MessageMutations.deleteWhere { MessageMutations.chatId eq chatId }
        SenderKeyDistributions.deleteWhere { SenderKeyDistributions.chatId eq chatId }
        AiPreferences.deleteWhere { AiPreferences.chatId eq chatId }
        ChatUserSettings.deleteWhere { ChatUserSettings.chatId eq chatId }
        GroupAuditLogs.deleteWhere { GroupAuditLogs.chatId eq chatId }
        ChatParticipants.deleteWhere { ChatParticipants.chatId eq chatId }
        Chats.deleteWhere { Chats.id eq chatId }
        return attachmentIds
    }

    fun getPrivacy(userId: String): UserPrivacyResponse? {
        return transaction {
            Users.selectAll().where { Users.id eq userId }.firstOrNull()?.let {
                UserPrivacyResponse(
                    showOnline = it[Users.showOnline],
                    showStatus = it[Users.showStatus],
                    searchable = it[Users.searchable],
                    defaultPostVisibility = normalizeVisibility(it[Users.defaultPostVisibility])
                )
            }
        }
    }

    fun updatePrivacyWithTransitions(
        userId: String,
        showOnline: Boolean? = null,
        showStatus: Boolean? = null,
        searchable: Boolean? = null,
        defaultPostVisibility: String? = null
    ): PrivacyUpdateResult? {
        return transaction {
            val normalizedVisibility = defaultPostVisibility?.let(::normalizeVisibility)
            val previous = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction null
            Users.update({ Users.id eq userId }) {
                if (showOnline != null) it[Users.showOnline] = showOnline
                if (showStatus != null) it[Users.showStatus] = showStatus
                if (searchable != null) it[Users.searchable] = searchable
                if (normalizedVisibility != null) it[Users.defaultPostVisibility] = normalizedVisibility
            }
            val privacy = UserPrivacyResponse(
                showOnline = showOnline ?: previous[Users.showOnline],
                showStatus = showStatus ?: previous[Users.showStatus],
                searchable = searchable ?: previous[Users.searchable],
                defaultPostVisibility = normalizedVisibility
                    ?: normalizeVisibility(previous[Users.defaultPostVisibility])
            )
            PrivacyUpdateResult(
                privacy = privacy,
                onlineRevoked = previous[Users.showOnline] && !privacy.showOnline,
                statusRevoked = previous[Users.showStatus] && !privacy.showStatus
            )
        }
    }

    private fun ResultRow.toPrivateUser(isOnlineOverride: Boolean? = null): UserResponse {
        return UserResponse(
            id = this[Users.id],
            name = this[Users.name],
            email = this[Users.email],
            avatar = this[Users.avatar],
            status = this[Users.status],
            isOnline = isOnlineOverride ?: this[Users.isOnline],
            isModerator = this[Users.isModerator],
            lastSeen = this[Users.lastSeen],
            username = this[Users.username]
        )
    }

    private fun ResultRow.toPublicUser(lastSeenVisible: Boolean = false, anonymous: Boolean = false): UserResponse {
        if (this[Users.deletedAt] != null) {
            return UserResponse(
                id = this[Users.id],
                name = DELETED_USER_NAME,
                email = "",
                avatar = null,
                status = "账号已注销",
                isOnline = false
            )
        }
        // 匿名访问（公开主页）隐藏 status/isOnline/lastSeen——外部链接页只需展示姓名与头像。
        val onlineVisible = !anonymous && this[Users.showOnline] && this[Users.isOnline]
        val statusVisible = if (anonymous) "" else if (this[Users.showStatus]) this[Users.status] else ""
        // 精确 lastSeen 仅对「存在 1:1 会话」的 viewer 可见（8.30 隐私修复）；
        // 目录/搜索/公开主页一律 0，与好友列表、群成员等界面一致。
        val lastSeenVisibleValue = if (!anonymous && lastSeenVisible) this[Users.lastSeen] else 0
        return UserResponse(
            id = this[Users.id],
            name = this[Users.name],
            email = "",
            avatar = this[Users.avatar],
            status = statusVisible,
            isOnline = onlineVisible,
            lastSeen = lastSeenVisibleValue,
            username = this[Users.username]
        )
    }

    /** 双向拉黑集合（viewer 拉黑的人 + 拉黑 viewer 的人）。 */
    private fun blockedUserIdsInTx(viewerId: String): Set<String> {
        val blockedByMe = BlockedUsers.selectAll()
            .where { BlockedUsers.blockerId eq viewerId }
            .map { it[BlockedUsers.blockedId] }
            .toSet()
        val blockedMe = BlockedUsers.selectAll()
            .where { BlockedUsers.blockedId eq viewerId }
            .map { it[BlockedUsers.blockerId] }
            .toSet()
        return blockedByMe + blockedMe
    }

    /** viewer 与 target 是否存在 1:1 会话（lastSeen 可见性判定）。 */
    private fun hasDirectChatInTx(viewerId: String, targetId: String): Boolean {
        val pairKey = if (viewerId <= targetId) "$viewerId:$targetId" else "$targetId:$viewerId"
        return DirectChatPairs.selectAll()
            .where { DirectChatPairs.pairKey eq pairKey }
            .firstOrNull() != null
    }

    /** 通过唯一用户名查找用户（不含 @ 前缀，大小写不敏感）——公开主页专用（匿名脱敏）。 */
    fun findByUsername(username: String): UserResponse? {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return null
        return transaction {
            Users.selectAll().where { Users.username eq clean }.firstOrNull()
                ?.toPublicUser(anonymous = true)
        }
    }

    /** 设置/更新当前用户的用户名（唯一、小写、去 @ 前缀） */
    fun setUsername(userId: String, username: String): String? {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank() || clean.length < 3 || clean.length > 50) return null
        if (!clean.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return try {
            transaction {
                val existing = Users.selectAll().where { Users.username eq clean }.firstOrNull()
                if (existing != null && existing[Users.id] != userId) return@transaction null
                Users.update({ Users.id eq userId }) {
                    it[Users.username] = clean
                }
                clean
            }
        } catch (_: Exception) { null }
    }

    /** 清除用户名（设为 null） */
    fun clearUsername(userId: String): Boolean = transaction {
        Users.update({ Users.id eq userId }) { it[Users.username] = null }
        true
    }

    /**
     * @return true 已写入/已存在；false 无效目标（自己 / 空 / 目标不存在或已注销）
     */
    fun blockUser(blockerId: String, blockedId: String): Boolean {
        if (blockerId.isBlank() || blockedId.isBlank() || blockerId == blockedId) return false
        return transaction {
            val users = lockUserPair(blockerId, blockedId)
            if (users.size != 2 || users.any { it[Users.deletedAt] != null }) return@transaction false
            val existing = BlockedUsers.selectAll().where {
                (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId)
            }
            if (existing.empty()) {
                BlockedUsers.insert {
                    it[BlockedUsers.blockerId] = blockerId
                    it[BlockedUsers.blockedId] = blockedId
                }
            }
            val (low, high) = orderedPair(blockerId, blockedId)
            Friendships.deleteWhere {
                (Friendships.userLowId eq low) and (Friendships.userHighId eq high)
            }
            val now = System.currentTimeMillis()
            FriendRequests.update({
                (FriendRequests.status eq "PENDING") and (
                    ((FriendRequests.fromUserId eq blockerId) and (FriendRequests.toUserId eq blockedId)) or
                        ((FriendRequests.fromUserId eq blockedId) and (FriendRequests.toUserId eq blockerId))
                    )
            }) {
                it[FriendRequests.status] = "CANCELLED"
                it[FriendRequests.updatedAt] = now
            }
            true
        }
    }

    private fun orderedPair(a: String, b: String): Pair<String, String> =
        if (a <= b) a to b else b to a

    private fun lockUserPair(a: String, b: String) = Users.selectAll()
        .where { Users.id inList listOf(a, b).distinct() }
        .orderBy(Users.id to SortOrder.ASC)
        .forUpdate()
        .toList()

    fun unblockUser(blockerId: String, blockedId: String) {
        transaction {
            lockUserPair(blockerId, blockedId)
            BlockedUsers.deleteWhere {
                (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId)
            }
        }
    }
    fun getBlockedUsers(userId: String): List<String> {
        return transaction { BlockedUsers.selectAll().where { BlockedUsers.blockerId eq userId }.map { it[BlockedUsers.blockedId] } }
    }
    fun getBlockedUserDetails(userId: String): List<UserResponse> {
        return transaction {
            val blockedIds = BlockedUsers.selectAll()
                .where { BlockedUsers.blockerId eq userId }
                .map { it[BlockedUsers.blockedId] }
            if (blockedIds.isEmpty()) {
                emptyList()
            } else {
                Users.selectAll()
                    .where { Users.id inList blockedIds }
                    .map { it.toPublicUser() }
                    .sortedBy { blockedIds.indexOf(it.id) }
            }
        }
    }
    fun hasBlocked(blockerId: String, blockedId: String): Boolean {
        return transaction {
            !BlockedUsers.selectAll()
                .where { (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId) }
                .empty()
        }
    }

    fun isBlockedEitherWay(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank() || a == b) return false
        return hasBlocked(a, b) || hasBlocked(b, a)
    }

    /**
     * 批量双向拉黑查询（8.30 性能优化 A1）：一次 SQL 返回 [targetIds] 中与 [viewerId]
     * 存在任一方向拉黑的集合。供群消息 fanout / push 收件人过滤使用，替代逐成员事务。
     */
    fun blockedEitherWayIdsInTx(viewerId: String, targetIds: List<String>): Set<String> {
        if (viewerId.isBlank() || targetIds.isEmpty()) return emptySet()
        return transaction {
            val blockedByViewer = BlockedUsers.select(BlockedUsers.blockedId)
                .where { (BlockedUsers.blockerId eq viewerId) and (BlockedUsers.blockedId inList targetIds) }
                .map { it[BlockedUsers.blockedId] }
                .toSet()
            val blockedViewer = BlockedUsers.select(BlockedUsers.blockerId)
                .where { (BlockedUsers.blockedId eq viewerId) and (BlockedUsers.blockerId inList targetIds) }
                .map { it[BlockedUsers.blockerId] }
                .toSet()
            blockedByViewer + blockedViewer
        }
    }

    private fun normalizeVisibility(value: String): String {
        return if (value in ALLOWED_POST_VISIBILITIES) value else "PUBLIC"
    }

    private fun String.normalizedEmail(): String = trim().lowercase()

    private fun isValidAvatarUrl(value: String): Boolean {
        if (value.length > MAX_AVATAR_LENGTH) return false
        val prefix = "/api/files/avatar/"
        // Allow relative path or absolute URL pointing to this server only.
        return value.startsWith(prefix) ||
            value.startsWith(com.maodouchat.server.config.ServerConfig.baseUrl.trimEnd('/') + prefix)
    }

    fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        private const val MAX_NAME_LENGTH = 50
        private const val MAX_STATUS_LENGTH = 80
        private const val MAX_AVATAR_LENGTH = 500
        private const val DELETED_USER_NAME = "已注销用户"
        private val secureRandom = SecureRandom()
        val ALLOWED_POST_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")
    }
}

/** Result of account deactivation; [orphanedAttachmentIds] need disk cleanup by the caller. */
data class AccountDeactivationResult(
    val deletedAt: Long,
    val orphanedAttachmentIds: List<String> = emptyList(),
    val avatarUrl: String? = null
)

data class AvatarReplacementResult(
    val previousUrl: String?,
    val currentUrl: String?
)

data class PrivacyUpdateResult(
    val privacy: UserPrivacyResponse,
    val onlineRevoked: Boolean,
    val statusRevoked: Boolean
)

private enum class Restriction { MESSAGE, POST, SUSPEND }
