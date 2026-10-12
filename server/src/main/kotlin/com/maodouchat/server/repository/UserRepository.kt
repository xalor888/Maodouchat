package com.maodouchat.server.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.UserPrivacyResponse
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.service.AccountLifecycleService
import com.maodouchat.server.service.BlockService
import com.maodouchat.server.service.CredentialService
import com.maodouchat.server.service.PrivacyService
import com.maodouchat.server.service.ProfileService
import com.maodouchat.server.service.normalizedEmail
import com.maodouchat.server.service.toPrivateUser
import com.maodouchat.server.service.toPublicUser
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
     *
     * G328c：邮箱匹配加了**准入条件**——只有查询本身长得像邮箱（含 `@`）且本地部分
     * 至少 3 个字符时才参与 email 匹配。原因：`lower(email) like '%q%'` 配合 2 字符查询
     * 等于一个「这个地址是否注册过」的在线探测器（审计点名的「已认证用户可枚举邮箱」）。
     * 按邮箱加好友是正当功能，所以不能直接去掉匹配，而是把它限制在「用户已经知道
     * 完整地址」的形态上：`a@` 这类前缀扫描不再命中 email 列。
     */
    fun searchUsers(keyword: String, excludeUserId: String? = null, limit: Int = 30, viewerId: String? = null): List<UserResponse> {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return emptyList()
        val emailQueryAllowed = run {
            val at = trimmed.indexOf('@')
            at >= 3 && at < trimmed.length - 1
        }
        return transaction {
            val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%${escaped.lowercase()}%"
            val baseQuery = Users.selectAll().where {
                val textMatch = (Users.name.lowerCase() like pattern) or
                    (Users.id.lowerCase() like pattern) or
                    (Users.username.lowerCase() like pattern)
                if (emailQueryAllowed) {
                    (Users.searchable eq true) and (textMatch or (Users.email.lowerCase() like pattern))
                } else {
                    (Users.searchable eq true) and textMatch
                }
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

    /** B13：批量处置字段（SUSPEND/POST/MESSAGE 单项，或组合清除）。 */
    enum class UserDispositionField { SUSPEND, POST, MESSAGE, MESSAGE_AND_POST, ALL }

    /** B13：批量处置语义——EXTEND 保长（maxOf）、SET 直接覆盖、CLEAR 置零。 */
    enum class UserDispositionMode { EXTEND, SET, CLEAR }

    data class BulkDispositionResult(val updated: List<String>, val skipped: List<String>)

    /** B13：批量用户设置字段（searchable/showStatus/showOnline 布尔开关）。 */
    enum class UserSettingsField { SEARCHABLE, SHOW_STATUS, SHOW_ONLINE }

    /**
     * 修改密码：先校验旧密码，成功后用 BCrypt cost=12 重哈希
     * @return true 改密成功；false 用户不存在或旧密码错误
     */
    internal val credentialService = CredentialService()

    internal val accountLifecycleService = AccountLifecycleService()

    internal val privacyService = PrivacyService()

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
    internal val profileService = ProfileService()

    internal val blockService = BlockService()

    private companion object {
        private const val MAX_NAME_LENGTH = 50
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
         * （锁本体随 register 迁入 CredentialService。）
         */
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

internal enum class Restriction { MESSAGE, POST, SUSPEND }
