package com.maodouchat.server.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.config.AdminAccess
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
import org.jetbrains.exposed.sql.select
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
        val registerTx = {
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
                // 主管理员（调用方在 registrationLock 内串行执行，见 companion 注释）。
                if (ServerConfig.bootstrapFirstUserAsAdmin &&
                    Users.selectAll().where { Users.id neq id }.empty()
                ) {
                    com.maodouchat.server.config.AdminAccess.grantAdmin(id)
                }
                UserResponse(id, safeName, normalizedEmail, status = "在线", isModerator = normalizedEmail in ServerConfig.moderatorEmails)
            }
        }
        return try {
            if (ServerConfig.bootstrapFirstUserAsAdmin) {
                synchronized(registrationLock) { registerTx() }
            } else {
                registerTx()
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
                .firstOrNull()?.let { row ->
                    onlineVisibleToAnyone(row[Users.showOnline], row[Users.onlineVisibility])
                } ?: false
        }
    }

    fun shouldShowOnlineTo(targetId: String, viewerId: String?): Boolean {
        if (viewerId.isNullOrBlank()) return false
        if (targetId == viewerId) return true
        return transaction {
            val row = Users.selectAll().where { Users.id eq targetId }.firstOrNull() ?: return@transaction false
            onlineVisibleToViewer(
                showOnline = row[Users.showOnline],
                visibility = row[Users.onlineVisibility],
                targetId = targetId,
                viewerId = viewerId
            )
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

    /**
     * B11：管理员处置统一入口——写字段 + 审计同一事务（actor/reason/before-after 入 audit detail）。
     * 替代路由层散落的裸 Users.update + ModerationAuditLog.insert。
     */
    fun applyUserSuspension(actorId: String, userId: String, until: Long, auditDetail: String): Boolean = transaction {
        val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
            it[suspendedUntil] = until
        }
        if (changed == 0) return@transaction false
        ModerationAuditLog.insert {
            it[ModerationAuditLog.userId] = userId
            it[ModerationAuditLog.action] = "ADMIN_STATUS_UPDATE"
            it[ModerationAuditLog.detail] = auditDetail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
            it[ModerationAuditLog.actorId] = actorId
            it[createdAt] = System.currentTimeMillis()
        }
        true
    }

    fun applyUserPostRestriction(actorId: String, userId: String, until: Long, auditDetail: String): Boolean = transaction {
        val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
            it[postRestrictedUntil] = until
        }
        if (changed == 0) return@transaction false
        ModerationAuditLog.insert {
            it[ModerationAuditLog.userId] = userId
            it[ModerationAuditLog.action] = "ADMIN_POST_RESTRICT"
            it[ModerationAuditLog.detail] = auditDetail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
            it[ModerationAuditLog.actorId] = actorId
            it[createdAt] = System.currentTimeMillis()
        }
        true
    }

    fun applyUserMessageRestriction(actorId: String, userId: String, until: Long, auditDetail: String): Boolean = transaction {
        val changed = Users.update({ (Users.id eq userId) and Users.deletedAt.isNull() }) {
            it[messageRestrictedUntil] = until
        }
        if (changed == 0) return@transaction false
        ModerationAuditLog.insert {
            it[ModerationAuditLog.userId] = userId
            it[ModerationAuditLog.action] = "ADMIN_MESSAGE_RESTRICT"
            it[ModerationAuditLog.detail] = auditDetail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
            it[ModerationAuditLog.actorId] = actorId
            it[createdAt] = System.currentTimeMillis()
        }
        true
    }

    /** B13：批量处置字段（SUSPEND/POST/MESSAGE 单项，或组合清除）。 */
    enum class UserDispositionField { SUSPEND, POST, MESSAGE, MESSAGE_AND_POST, ALL }

    /** B13：批量处置语义——EXTEND 保长（maxOf）、SET 直接覆盖、CLEAR 置零。 */
    enum class UserDispositionMode { EXTEND, SET, CLEAR }

    data class BulkDispositionResult(val updated: List<String>, val skipped: List<String>)

    /**
     * B13：批量处置统一命令——批量存在性检查 + 单事务逐 id 写入 + 逐 id 审计。
     * 收敛 AdminBulkRouting 里 ~15 段「skip 自己/主管理员/不存在 → update → audit」重复实现。
     */
    fun applyBulkDisposition(
        actorId: String,
        ids: List<String>,
        field: UserDispositionField,
        mode: UserDispositionMode,
        until: Long,
        action: String,
        detailFor: (effective: Long) -> String,
    ): BulkDispositionResult {
        val existing = transaction {
            Users.select(Users.id).where { (Users.id inList ids) and Users.deletedAt.isNull() }.map { it[Users.id] }.toSet()
        }
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        transaction {
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id) || id !in existing) {
                    skipped += id
                    return@forEach
                }
                val row = Users.selectAll().where { Users.id eq id }.firstOrNull()
                if (row == null) {
                    skipped += id
                    return@forEach
                }
                val effective = when (mode) {
                    UserDispositionMode.CLEAR -> 0L
                    UserDispositionMode.SET -> until
                    UserDispositionMode.EXTEND -> if (until <= 0L) 0L else maxOf(currentDisposition(row, field), until)
                }
                Users.update({ (Users.id eq id) and Users.deletedAt.isNull() }) {
                    when (field) {
                        UserDispositionField.SUSPEND -> it[Users.suspendedUntil] = effective
                        UserDispositionField.POST -> it[Users.postRestrictedUntil] = effective
                        UserDispositionField.MESSAGE -> it[Users.messageRestrictedUntil] = effective
                        UserDispositionField.MESSAGE_AND_POST -> {
                            it[Users.messageRestrictedUntil] = effective
                            it[Users.postRestrictedUntil] = effective
                        }
                        UserDispositionField.ALL -> {
                            it[Users.messageRestrictedUntil] = effective
                            it[Users.postRestrictedUntil] = effective
                            it[Users.suspendedUntil] = effective
                        }
                    }
                }
                ModerationAuditLog.insert {
                    it[ModerationAuditLog.userId] = id
                    it[ModerationAuditLog.action] = action
                    it[ModerationAuditLog.detail] = detailFor(effective).take(200)
                    it[ModerationAuditLog.actorId] = actorId
                    it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
                }
                updated += id
            }
        }
        return BulkDispositionResult(updated, skipped)
    }

    private fun currentDisposition(row: ResultRow, field: UserDispositionField): Long = when (field) {
        UserDispositionField.SUSPEND -> row[Users.suspendedUntil]
        UserDispositionField.POST -> row[Users.postRestrictedUntil]
        UserDispositionField.MESSAGE -> row[Users.messageRestrictedUntil]
        UserDispositionField.MESSAGE_AND_POST -> maxOf(row[Users.messageRestrictedUntil], row[Users.postRestrictedUntil])
        UserDispositionField.ALL -> maxOf(row[Users.suspendedUntil], row[Users.postRestrictedUntil], row[Users.messageRestrictedUntil])
    }

    fun updateProfile(userId: String, name: String? = null, status: String? = null) {
        transaction {
            val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction
            if (row[Users.deletedAt] != null) return@transaction
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

    private val accountLifecycleService = AccountLifecycleService()

    fun deleteAccount(userId: String, password: String): AccountDeactivationResult? = accountLifecycleService.deleteAccount(userId, password)
    fun adminDeactivateAccount(userId: String, actorId: String): AccountDeactivationResult? = accountLifecycleService.adminDeactivateAccount(userId, actorId)


    private val privacyService = PrivacyService()

    fun getPrivacy(userId: String): UserPrivacyResponse? = privacyService.getPrivacy(userId)
    fun updatePrivacyWithTransitions(
        userId: String,
        showOnline: Boolean? = null,
        showStatus: Boolean? = null,
        searchable: Boolean? = null,
        defaultPostVisibility: String? = null,
        onlineVisibility: String? = null
    ): PrivacyUpdateResult? = privacyService.updatePrivacyWithTransitions(userId, showOnline, showStatus, searchable, defaultPostVisibility, onlineVisibility)


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
        } catch (error: Exception) {
            // 仅唯一冲突（并发抢注同名）映射「已占用」；DB 故障等不得伪装成冲突静默失败
            if (isUniqueViolation(error)) null else throw error
        }
    }

    /** 清除用户名（设为 null） */
    fun clearUsername(userId: String): Boolean = transaction {
        Users.update({ Users.id eq userId }) { it[Users.username] = null }
        true
    }

    /**
     * @return true 已写入/已存在；false 无效目标（自己 / 空 / 目标不存在或已注销）
     */
    private val blockService = BlockService()

    fun blockUser(blockerId: String, blockedId: String): Boolean = blockService.blockUser(blockerId, blockedId)
    fun unblockUser(blockerId: String, blockedId: String) = blockService.unblockUser(blockerId, blockedId)
    fun getBlockedUsers(userId: String): List<String> = blockService.getBlockedUsers(userId)
    fun hasBlocked(blockerId: String, blockedId: String): Boolean = blockService.hasBlocked(blockerId, blockedId)
    fun isBlockedEitherWay(a: String, b: String): Boolean = blockService.isBlockedEitherWay(a, b)
    fun blockedEitherWayIdsInTx(viewerId: String, targetIds: List<String>): Set<String> = blockService.blockedEitherWayIdsInTx(viewerId, targetIds)
    fun hasBlockedPairInTx(userIds: List<String>): Boolean = blockService.hasBlockedPairInTx(userIds)

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
        val ALLOWED_ONLINE_VISIBILITIES = setOf("everyone", "contacts", "nobody")

        fun normalizeOnlineVisibility(value: String?, showOnlineFallback: Boolean = true): String {
            val raw = value?.trim()?.lowercase().orEmpty()
            return when (raw) {
                "everyone", "contacts", "nobody" -> raw
                else -> if (showOnlineFallback) "everyone" else "nobody"
            }
        }

        fun onlineVisibleToAnyone(showOnline: Boolean, visibility: String?): Boolean {
            if (!showOnline) return false
            return normalizeOnlineVisibility(visibility, showOnline) != "nobody"
        }

        fun onlineVisibleToViewer(
            showOnline: Boolean,
            visibility: String?,
            targetId: String,
            viewerId: String
        ): Boolean {
            if (targetId == viewerId) return true
            if (!showOnline) return false
            return when (normalizeOnlineVisibility(visibility, showOnline)) {
                "everyone" -> true
                "contacts" -> FriendRepository().areFriends(targetId, viewerId)
                else -> false
            }
        }

        /**
         * 8.49 修复 BOOTSTRAP_FIRST_USER_AS_ADMIN 并发竞态：READ COMMITTED 下两个并发
         * 注册事务互看不到对方未提交的插入，双双判定自己是"第一个用户"→双主管理员。
         * 单实例部署（compose 固定单 server）下用进程锁把整个注册事务串行化：
         * 先提交者成为唯一"第一个"，后到者在锁内能看到已提交行。注册本身低频，锁开销可忽略。
         */
        private val registrationLock = Any()
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
