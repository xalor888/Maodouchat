package com.maodouchat.server.repository

// 用户查询簇（公开资料/目录/搜索/邮箱）：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.service.normalizedEmail
import com.maodouchat.server.service.toPrivateUser
import com.maodouchat.server.service.toPublicUser
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun UserRepository.getById(userId: String): UserResponse? {
    return transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()
            ?.takeIf { it[Users.deletedAt] == null }
            ?.toPrivateUser()
    }
}


/** 当前用户公开形态（含 lastSeen 精确值，8.32 一致性：/api/users/me/public 不再返回私有字段）。 */
fun UserRepository.getPublicMe(userId: String): UserResponse? {
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
fun UserRepository.getPublicById(userId: String, viewerId: String? = null): UserResponse? {
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
fun UserRepository.getAll(limit: Int = 100, offset: Int = 0, viewerId: String? = null): List<UserResponse> {
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
fun UserRepository.searchUsers(keyword: String, excludeUserId: String? = null, limit: Int = 30, viewerId: String? = null): List<UserResponse> {
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


fun UserRepository.getByEmail(email: String): UserResponse? {
    return transaction {
        Users.selectAll().where { Users.email eq email.normalizedEmail() }.firstOrNull()
            ?.takeIf { it[Users.deletedAt] == null }
            ?.toPrivateUser()
    }
}


private fun UserRepository.blockedUserIdsInTx(viewerId: String): Set<String> {
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
private fun UserRepository.hasDirectChatInTx(viewerId: String, targetId: String): Boolean {
    val pairKey = if (viewerId <= targetId) "$viewerId:$targetId" else "$targetId:$viewerId"
    return DirectChatPairs.selectAll()
        .where { DirectChatPairs.pairKey eq pairKey }
        .firstOrNull() != null
}
