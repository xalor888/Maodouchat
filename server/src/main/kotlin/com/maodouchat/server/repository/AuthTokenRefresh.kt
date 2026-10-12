package com.maodouchat.server.repository

// refresh token 会话生命周期簇：原 AuthTokenRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import org.jetbrains.exposed.sql.count
import com.maodouchat.server.db.RevokedAccessTokens
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

fun AuthTokenRepository.issueRefreshToken(userId: String, existingSessionId: String? = null): IssuedRefreshToken {
    val token = generateRefreshToken()
    val now = System.currentTimeMillis()
    val expiresAt = now + AuthTokenRepository.REFRESH_TOKEN_VALIDITY_MS
    val hash = hashToken(token)
    val sessionId = existingSessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    transaction {
        val user = Users.selectAll()
            .where { Users.id eq userId }
            .forUpdate()
            .firstOrNull()
        if (user == null || user[Users.deletedAt] != null || user[Users.suspendedUntil] > now) {
            throw InactiveAuthSessionException()
        }
        if (existingSessionId.isNullOrBlank()) {
            AuthSessions.insert {
                it[id] = sessionId
                it[AuthSessions.userId] = userId
                it[signalDeviceId] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[revokedAt] = null
            }
            // 8.34 修复：每用户活跃会话数上限——登录次数无限则会话/refresh token 无限膨胀
            //（每会话 30 天可轮换续命）。超出后按创建时间淘汰最旧会话（用户行 forUpdate 锁
            // 保证同用户并发登录下此判定串行）。
            val activeSessions = AuthSessions.select(AuthSessions.id, AuthSessions.createdAt)
                .where { (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull() }
                .orderBy(AuthSessions.createdAt to SortOrder.ASC)
                .toList()
            val excess = activeSessions.size - AuthTokenRepository.MAX_ACTIVE_SESSIONS
            if (excess > 0) {
                activeSessions.take(excess).forEach { row ->
                    val staleSessionId = row[AuthSessions.id]
                    RefreshTokens.update({
                        (RefreshTokens.sessionId eq staleSessionId) and RefreshTokens.revokedAt.isNull()
                    }) {
                        it[revokedAt] = now
                    }
                    AuthSessions.update({ AuthSessions.id eq staleSessionId }) {
                        it[revokedAt] = now
                    }
                }
            }
        } else {
            val session = AuthSessions.selectAll()
                .where { (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId) }
                .forUpdate()
                .firstOrNull()
            if (session == null || session[AuthSessions.revokedAt] != null) {
                throw InactiveAuthSessionException()
            }
            AuthSessions.update({ AuthSessions.id eq sessionId }) {
                it[updatedAt] = now
            }
        }
        RefreshTokens.insert {
            it[tokenHash] = hash
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.sessionId] = sessionId
            it[createdAt] = now
            it[RefreshTokens.expiresAt] = expiresAt
            it[revokedAt] = null
        }
    }
    return IssuedRefreshToken(token, expiresAt, sessionId)
}

/**
 * 单事务原子轮换：行锁 → 校验用户/封禁 → 通过后才 revoke。
 * 封禁/账号缺失时不吊销 refresh，避免 peek→consume 竞态把有效 token 烧掉。
 */
fun AuthTokenRepository.rotateIfEligible(refreshToken: String): AuthTokenRepository.RotateRefreshResult {
    val hash = hashToken(refreshToken)
    val now = System.currentTimeMillis()
    val replacementToken = generateRefreshToken()
    val replacementHash = hashToken(replacementToken)
    val replacementExpiresAt = now + AuthTokenRepository.REFRESH_TOKEN_VALIDITY_MS
    return transaction {
        val initialRow = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .firstOrNull()
            ?: return@transaction AuthTokenRepository.RotateRefreshResult.InvalidToken
        val userId = initialRow[RefreshTokens.userId]
        val sessionId = initialRow[RefreshTokens.sessionId]
        val user = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
        if (user == null || user[Users.deletedAt] != null) {
            return@transaction AuthTokenRepository.RotateRefreshResult.UserMissing
        }
        val session = sessionId.takeIf { it.isNotBlank() }?.let { id ->
            AuthSessions.selectAll()
                .where { (AuthSessions.id eq id) and (AuthSessions.userId eq userId) }
                .forUpdate()
                .firstOrNull()
        }
        // 用户 → auth session → refresh 的固定锁顺序与设备删除一致，避免交叉死锁。
        val row = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction AuthTokenRepository.RotateRefreshResult.InvalidToken
        if (row[RefreshTokens.revokedAt] != null) {
            if (session != null && session[AuthSessions.revokedAt] == null) {
                revokeSessionInCurrentTransaction(userId, sessionId, now)
                return@transaction AuthTokenRepository.RotateRefreshResult.SessionCompromised(userId, sessionId)
            }
            return@transaction AuthTokenRepository.RotateRefreshResult.InvalidToken
        }
        if (row[RefreshTokens.expiresAt] <= now) {
            val hasActiveReplacement = sessionId.isNotBlank() && RefreshTokens.selectAll().where {
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    (RefreshTokens.tokenHash neq hash) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.firstOrNull() != null
            if (!hasActiveReplacement && session != null && session[AuthSessions.revokedAt] == null) {
                revokeSessionInCurrentTransaction(userId, sessionId, now)
            }
            return@transaction AuthTokenRepository.RotateRefreshResult.InvalidToken
        }
        if (user[Users.suspendedUntil] > now) {
            return@transaction AuthTokenRepository.RotateRefreshResult.UserSuspended
        }
        if (session == null || session[AuthSessions.revokedAt] != null) {
            RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
                it[revokedAt] = now
            }
            return@transaction AuthTokenRepository.RotateRefreshResult.InvalidToken
        }
        RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
            it[revokedAt] = now
        }
        AuthSessions.update({ AuthSessions.id eq sessionId }) {
            it[updatedAt] = now
        }
        RefreshTokens.insert {
            it[tokenHash] = replacementHash
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.sessionId] = sessionId
            it[createdAt] = now
            it[expiresAt] = replacementExpiresAt
            it[revokedAt] = null
        }
        AuthTokenRepository.RotateRefreshResult.Success(userId, sessionId, replacementToken, replacementExpiresAt)
    }
}

fun AuthTokenRepository.revoke(refreshToken: String): Boolean {
    return revokeAndGetSession(refreshToken) != null
}

/**
 * 吊销 refresh 所属的整个登录会话，并返回其身份供 WebSocket / 推送清理。
 * 旧版 refresh 没有 sessionId 时只吊销该 token，且不能再用于轮换。
 */
fun AuthTokenRepository.revokeAndGetSession(refreshToken: String): AuthTokenRepository.RevokedSession? {
    val hash = hashToken(refreshToken)
    val now = System.currentTimeMillis()
    return transaction {
        val initialRow = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .firstOrNull()
            ?: return@transaction null
        val userId = initialRow[RefreshTokens.userId]
        val sessionId = initialRow[RefreshTokens.sessionId].takeIf { it.isNotBlank() }
        Users.selectAll()
            .where { Users.id eq userId }
            .forUpdate()
            .firstOrNull()
        if (sessionId != null) {
            AuthSessions.selectAll()
                .where { (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId) }
                .forUpdate()
                .firstOrNull()
        }
        val row = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction null
        if (sessionId == null) {
            RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
                it[revokedAt] = now
            }
        } else {
            AuthSessions.update({
                (AuthSessions.id eq sessionId) and
                    (AuthSessions.userId eq userId) and
                    AuthSessions.revokedAt.isNull()
            }) {
                it[revokedAt] = now
                it[updatedAt] = now
            }
            RefreshTokens.update({
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    RefreshTokens.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
        }
        AuthTokenRepository.RevokedSession(userId, sessionId)
    }
}

fun AuthTokenRepository.revokeSession(userId: String, sessionId: String): Boolean {
    if (userId.isBlank() || sessionId.isBlank()) return false
    val now = System.currentTimeMillis()
    return transaction {
        Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction false
        AuthSessions.selectAll().where {
            (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId)
        }.forUpdate().firstOrNull() ?: return@transaction false
        revokeSessionInCurrentTransaction(userId, sessionId, now) > 0
    }
}

fun AuthTokenRepository.listActiveRefreshSessions(userId: String, includeRevoked: Boolean = false): List<AuthTokenRepository.RefreshSessionRow> = transaction {
    val now = System.currentTimeMillis()
    val activeSessionIds = if (includeRevoked) emptySet() else {
        AuthSessions.select(AuthSessions.id).where {
            (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
        }.map { it[AuthSessions.id] }.toSet()
    }
    RefreshTokens.selectAll()
        .where { RefreshTokens.userId eq userId }
        .orderBy(
            RefreshTokens.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            RefreshTokens.tokenHash to org.jetbrains.exposed.sql.SortOrder.DESC
        )
        .mapNotNull { row ->
            val revoked = row[RefreshTokens.revokedAt]
            if (!includeRevoked && (
                    revoked != null ||
                        row[RefreshTokens.expiresAt] <= now ||
                        row[RefreshTokens.sessionId] !in activeSessionIds
                    )
            ) return@mapNotNull null
            AuthTokenRepository.RefreshSessionRow(
                tokenHashPrefix = row[RefreshTokens.tokenHash].take(12),
                createdAt = row[RefreshTokens.createdAt],
                expiresAt = row[RefreshTokens.expiresAt],
                revokedAt = revoked
            )
        }
}

fun AuthTokenRepository.countActiveRefreshSessions(userId: String): Int = transaction {
    val now = System.currentTimeMillis()
    val activeSessionIds = AuthSessions.select(AuthSessions.id).where {
        (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
    }.map { it[AuthSessions.id] }
    if (activeSessionIds.isEmpty()) return@transaction 0
    RefreshTokens.selectAll()
        .where {
            (RefreshTokens.userId eq userId) and
                RefreshTokens.revokedAt.isNull() and
                (RefreshTokens.sessionId inList activeSessionIds) and
                (RefreshTokens.expiresAt greater now)
        }
        .count()
        .toInt()
}

/**
 * 8.48 修复 M7：批量统计多个用户的活跃 refresh 会话数（/sessions-summary-export
 * 此前逐用户 countActiveRefreshSessions → N+1，limit 2 万 → 2 万次查询）。
 * 一次事务内先取批量活跃 session，再 GROUP BY userId 聚合 refresh token。
 */
fun AuthTokenRepository.countActiveRefreshSessionsBatch(userIds: List<String>): Map<String, Int> = transaction {
    if (userIds.isEmpty()) return@transaction emptyMap()
    val now = System.currentTimeMillis()
    val sessions = AuthSessions.select(AuthSessions.id, AuthSessions.userId).where {
        (AuthSessions.userId inList userIds) and AuthSessions.revokedAt.isNull()
    }.map { it[AuthSessions.id] to it[AuthSessions.userId] }
    if (sessions.isEmpty()) return@transaction userIds.associateWith { 0 }
    val sessionIdToUser = sessions.toMap()
    val counts = RefreshTokens
        .slice(RefreshTokens.userId, RefreshTokens.tokenHash.count())
        .selectAll()
        .where {
            (RefreshTokens.userId inList userIds) and
                RefreshTokens.revokedAt.isNull() and
                (RefreshTokens.sessionId inList sessionIdToUser.keys) and
                (RefreshTokens.expiresAt greater now)
        }
        .groupBy(RefreshTokens.userId)
        .associate { it[RefreshTokens.userId] to it[RefreshTokens.tokenHash.count()].toInt() }
    userIds.associateWith { counts[it] ?: 0 }
}

fun AuthTokenRepository.revokeByHashPrefixWithSessions(userId: String, prefix: String): AuthTokenRepository.RevokeByPrefixResult {
    val p = prefix.trim().lowercase()
    // 8.34 修复：8 字符（32bit）SHA-256 前缀可碰撞误吊销无关会话 → 下限提高到 12 字符
    if (p.length < 12) return AuthTokenRepository.RevokeByPrefixResult(0, emptySet())
    val now = System.currentTimeMillis()
    return transaction {
        Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction AuthTokenRepository.RevokeByPrefixResult(0, emptySet())
        val candidates = RefreshTokens.selectAll()
            .where {
                (RefreshTokens.userId eq userId) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }
            .mapNotNull { row ->
                val hash = row[RefreshTokens.tokenHash]
                hash.takeIf { it.startsWith(p) }?.let { it to row[RefreshTokens.sessionId] }
            }
        val matchedSessionIds = candidates.mapNotNull { (_, sessionId) ->
            sessionId.takeIf(String::isNotBlank)
        }.toSortedSet()
        matchedSessionIds.forEach { sessionId ->
            AuthSessions.selectAll().where {
                (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId)
            }.forUpdate().firstOrNull()
        }
        val lockedMatches = candidates.mapNotNull { (tokenHash, sessionId) ->
            RefreshTokens.selectAll().where {
                (RefreshTokens.tokenHash eq tokenHash) and
                    (RefreshTokens.userId eq userId) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.forUpdate().firstOrNull()?.let { tokenHash to sessionId }
        }
        val activeMatchedSessionIds = lockedMatches.mapNotNull { (_, sessionId) ->
            sessionId.takeIf(String::isNotBlank)
        }.toSet()
        var revokedTokenCount = 0
        activeMatchedSessionIds.forEach { sessionId ->
            revokedTokenCount += revokeSessionInCurrentTransaction(userId, sessionId, now)
        }
        val legacyHashes = lockedMatches.mapNotNull { (tokenHash, sessionId) ->
            tokenHash.takeIf { sessionId.isBlank() }
        }
        legacyHashes.forEach { tokenHash ->
            RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) { it[revokedAt] = now }
        }
        AuthTokenRepository.RevokeByPrefixResult(
            // 准确统计：被撤销的 refresh token 总数 = 各会话撤销的 token 数 + 遗留哈希数，
            // 而非会话数（一个会话可能含多条 refresh token，旧算法会低估）。
            count = revokedTokenCount + legacyHashes.size,
            sessionIds = activeMatchedSessionIds
        )
    }
}

fun AuthTokenRepository.deleteExpired(now: Long = System.currentTimeMillis()): Int {
    val expiredSessions = transaction {
        RefreshTokens.select(RefreshTokens.userId, RefreshTokens.sessionId)
            .where {
                (RefreshTokens.expiresAt lessEq now) and
                    (RefreshTokens.sessionId neq "")
            }
            .map { it[RefreshTokens.userId] to it[RefreshTokens.sessionId] }
            .distinct()
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
    }
    expiredSessions.forEach { (userId, sessionId) ->
        transaction {
            Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction
            val session = AuthSessions.selectAll().where {
                (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId)
            }.forUpdate().firstOrNull() ?: return@transaction
            if (session[AuthSessions.revokedAt] != null) return@transaction
            val hasActiveRefresh = RefreshTokens.selectAll().where {
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.sessionId eq sessionId) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.firstOrNull() != null
            if (!hasActiveRefresh) revokeSessionInCurrentTransaction(userId, sessionId, now)
        }
    }
    return transaction {
        val refreshDeleted = RefreshTokens.deleteWhere { RefreshTokens.expiresAt lessEq now }
        RevokedAccessTokens.deleteWhere { RevokedAccessTokens.expiresAt lessEq now }
        // 8.34 修复：AuthSessions 行此前只置 revokedAt 从不物理删除，表随登录次数无界增长
        //（每用户每次登录一行，注销才硬删）。超过保留期（90 天）的已吊销会话行硬删，
        // 其 refresh token 已在上面/会话撤销时清理，无外键残留。
        AuthSessions.deleteWhere { AuthSessions.revokedAt less now - AuthTokenRepository.SESSION_ROW_RETENTION_MS }
        refreshDeleted
    }
}
