package com.maodouchat.server.repository

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.PushTokens
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class IssuedRefreshToken(val token: String, val expiresAt: Long, val sessionId: String)

// 8.42：改为 IllegalArgumentException——登录/注册签发 refresh 时会话并发失效应回 400
// （此前 IllegalStateException 落全局 Throwable 处理器回 500；refresh 路由对同条件显式 401/403）
class InactiveAuthSessionException : IllegalArgumentException("Authentication session is no longer active")

class AuthTokenRepository {

    fun issueRefreshToken(userId: String, existingSessionId: String? = null): IssuedRefreshToken {
        val token = generateRefreshToken()
        val now = System.currentTimeMillis()
        val expiresAt = now + REFRESH_TOKEN_VALIDITY_MS
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
                val excess = activeSessions.size - MAX_ACTIVE_SESSIONS
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

    sealed class RotateRefreshResult {
        data class Success(
            val userId: String,
            val sessionId: String,
            val refreshToken: String,
            val refreshExpiresAt: Long
        ) : RotateRefreshResult()
        data class SessionCompromised(val userId: String, val sessionId: String) : RotateRefreshResult()
        data object InvalidToken : RotateRefreshResult()
        data object UserSuspended : RotateRefreshResult()
        data object UserMissing : RotateRefreshResult()
    }

    /**
     * 单事务原子轮换：行锁 → 校验用户/封禁 → 通过后才 revoke。
     * 封禁/账号缺失时不吊销 refresh，避免 peek→consume 竞态把有效 token 烧掉。
     */
    fun rotateIfEligible(refreshToken: String): RotateRefreshResult {
        val hash = hashToken(refreshToken)
        val now = System.currentTimeMillis()
        val replacementToken = generateRefreshToken()
        val replacementHash = hashToken(replacementToken)
        val replacementExpiresAt = now + REFRESH_TOKEN_VALIDITY_MS
        return transaction {
            val initialRow = RefreshTokens.selectAll()
                .where { RefreshTokens.tokenHash eq hash }
                .firstOrNull()
                ?: return@transaction RotateRefreshResult.InvalidToken
            val userId = initialRow[RefreshTokens.userId]
            val sessionId = initialRow[RefreshTokens.sessionId]
            val user = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            if (user == null || user[Users.deletedAt] != null) {
                return@transaction RotateRefreshResult.UserMissing
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
                ?: return@transaction RotateRefreshResult.InvalidToken
            if (row[RefreshTokens.revokedAt] != null) {
                if (session != null && session[AuthSessions.revokedAt] == null) {
                    revokeSessionInCurrentTransaction(userId, sessionId, now)
                    return@transaction RotateRefreshResult.SessionCompromised(userId, sessionId)
                }
                return@transaction RotateRefreshResult.InvalidToken
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
                return@transaction RotateRefreshResult.InvalidToken
            }
            if (user[Users.suspendedUntil] > now) {
                return@transaction RotateRefreshResult.UserSuspended
            }
            if (session == null || session[AuthSessions.revokedAt] != null) {
                RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
                    it[revokedAt] = now
                }
                return@transaction RotateRefreshResult.InvalidToken
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
            RotateRefreshResult.Success(userId, sessionId, replacementToken, replacementExpiresAt)
        }
    }

    fun revoke(refreshToken: String): Boolean {
        return revokeAndGetSession(refreshToken) != null
    }

    data class RevokedSession(val userId: String, val sessionId: String?)

    /**
     * 吊销 refresh 所属的整个登录会话，并返回其身份供 WebSocket / 推送清理。
     * 旧版 refresh 没有 sessionId 时只吊销该 token，且不能再用于轮换。
     */
    fun revokeAndGetSession(refreshToken: String): RevokedSession? {
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
            RevokedSession(userId, sessionId)
        }
    }

    fun revokeSession(userId: String, sessionId: String): Boolean {
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

    data class RefreshSessionRow(
        val tokenHashPrefix: String,
        val createdAt: Long,
        val expiresAt: Long,
        val revokedAt: Long?
    )

    fun listActiveRefreshSessions(userId: String, includeRevoked: Boolean = false): List<RefreshSessionRow> = transaction {
        val now = System.currentTimeMillis()
        val activeSessionIds = if (includeRevoked) emptySet() else {
            AuthSessions.select(AuthSessions.id).where {
                (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull()
            }.map { it[AuthSessions.id] }.toSet()
        }
        RefreshTokens.selectAll()
            .where { RefreshTokens.userId eq userId }
            .orderBy(RefreshTokens.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .mapNotNull { row ->
                val revoked = row[RefreshTokens.revokedAt]
                if (!includeRevoked && (
                        revoked != null ||
                            row[RefreshTokens.expiresAt] <= now ||
                            row[RefreshTokens.sessionId] !in activeSessionIds
                        )
                ) return@mapNotNull null
                RefreshSessionRow(
                    tokenHashPrefix = row[RefreshTokens.tokenHash].take(12),
                    createdAt = row[RefreshTokens.createdAt],
                    expiresAt = row[RefreshTokens.expiresAt],
                    revokedAt = revoked
                )
            }
    }

    fun countActiveRefreshSessions(userId: String): Int = transaction {
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
    fun countActiveRefreshSessionsBatch(userIds: List<String>): Map<String, Int> = transaction {
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

    /**
     * Revoke active refresh sessions whose SHA-256 hex starts with [prefix] (min 8 chars).
     * Used by admin session inspector without exposing full hashes.
     */
    data class RevokeByPrefixResult(
        val count: Int,
        val sessionIds: Set<String>
    )

    fun revokeByHashPrefixWithSessions(userId: String, prefix: String): RevokeByPrefixResult {
        val p = prefix.trim().lowercase()
        // 8.34 修复：8 字符（32bit）SHA-256 前缀可碰撞误吊销无关会话 → 下限提高到 12 字符
        if (p.length < 12) return RevokeByPrefixResult(0, emptySet())
        val now = System.currentTimeMillis()
        return transaction {
            Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction RevokeByPrefixResult(0, emptySet())
            val candidates = RefreshTokens.selectAll()
                .where {
                    (RefreshTokens.userId eq userId) and RefreshTokens.revokedAt.isNull()
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
                        RefreshTokens.revokedAt.isNull()
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
            RevokeByPrefixResult(
                // 准确统计：被撤销的 refresh token 总数 = 各会话撤销的 token 数 + 遗留哈希数，
                // 而非会话数（一个会话可能含多条 refresh token，旧算法会低估）。
                count = revokedTokenCount + legacyHashes.size,
                sessionIds = activeMatchedSessionIds
            )
        }
    }

    fun getAccessTokenVersion(userId: String): Long {
        return transaction {
            Users.selectAll()
                .where { Users.id eq userId }
                .firstOrNull()
                ?.get(Users.accessTokenVersion)
                ?: 0
        }
    }

    fun rotateAccessTokenVersion(userId: String): Long {
        return transaction {
            // Version rotation is a global logout boundary. Revoke refresh/auth sessions in the
            // same user-row-locked transaction so a refresh token cannot mint the new version.
            val row = Users.selectAll()
                .where { Users.id eq userId }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction 0
            val next = row[Users.accessTokenVersion] + 1
            Users.update({ Users.id eq userId }) {
                it[Users.accessTokenVersion] = next
            }
            revokeAllForUserInCurrentTransaction(userId, System.currentTimeMillis())
            next
        }
    }

    private fun revokeSessionInCurrentTransaction(userId: String, sessionId: String, now: Long): Int {
        val sessionChanged = AuthSessions.update({
            (AuthSessions.id eq sessionId) and
                (AuthSessions.userId eq userId) and
                AuthSessions.revokedAt.isNull()
        }) {
            it[revokedAt] = now
            it[updatedAt] = now
        }
        // 返回实际撤销的 refresh token 数（而非仅会话数），供上层统计准确的吊销条数。
        val tokenChanged = RefreshTokens.update({
            (RefreshTokens.userId eq userId) and
                (RefreshTokens.sessionId eq sessionId) and
                RefreshTokens.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        }
        PushTokens.deleteWhere {
            (PushTokens.userId eq userId) and (PushTokens.authSessionId eq sessionId)
        }
        return sessionChanged + tokenChanged
    }

    private fun revokeAllForUserInCurrentTransaction(userId: String, now: Long): Int {
        AuthSessions.update({ (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull() }) {
            it[revokedAt] = now
            it[updatedAt] = now
        }
        val revoked = RefreshTokens.update({
            (RefreshTokens.userId eq userId) and RefreshTokens.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        }
        PushTokens.deleteWhere { PushTokens.userId eq userId }
        return revoked
    }

    fun isAccessTokenAllowed(
        userId: String,
        tokenVersion: Long,
        tokenId: String?,
        authSessionId: String? = null,
        requireAuthSession: Boolean = false
    ): Boolean {
        return transaction {
            val row = Users.selectAll()
                .where { Users.id eq userId }
                .firstOrNull()
                ?.takeIf { it[Users.deletedAt] == null }
                ?: return@transaction false
            // 封禁期内拒绝一切 access token（含封禁前签发、刷新竞态新发的）
            if (row[Users.suspendedUntil] > System.currentTimeMillis()) return@transaction false
            if (row[Users.accessTokenVersion] != tokenVersion) return@transaction false
            if (requireAuthSession && authSessionId.isNullOrBlank()) return@transaction false
            if (!authSessionId.isNullOrBlank()) {
                val sessionActive = AuthSessions.selectAll().where {
                    (AuthSessions.id eq authSessionId) and
                        (AuthSessions.userId eq userId) and
                        AuthSessions.revokedAt.isNull()
                }.firstOrNull() != null
                if (!sessionActive) return@transaction false
            }
            if (tokenId.isNullOrBlank()) return@transaction true
            RevokedAccessTokens.selectAll()
                .where { RevokedAccessTokens.tokenId eq tokenId }
                .empty()
        }
    }

    fun revokeAccessToken(accessToken: String): Boolean {
        val jwt = JwtConfig.verifyToken(accessToken) ?: return false
        val tokenId = jwt.id?.takeIf { it.isNotBlank() } ?: return false
        val userId = jwt.subject?.takeIf { it.isNotBlank() } ?: return false
        val expiresAt = jwt.expiresAt?.time ?: return false
        if (expiresAt <= System.currentTimeMillis()) return false
        val now = System.currentTimeMillis()
        // 并发 logout 撞 PK：外层 re-read，勿同事务 catch unique
        return try {
            transaction {
                if (!RevokedAccessTokens.selectAll().where { RevokedAccessTokens.tokenId eq tokenId }.empty()) {
                    return@transaction true
                }
                RevokedAccessTokens.insert {
                    it[RevokedAccessTokens.tokenId] = tokenId
                    it[RevokedAccessTokens.userId] = userId
                    it[RevokedAccessTokens.expiresAt] = expiresAt
                    it[revokedAt] = now
                }
                true
            }
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
            transaction {
                !RevokedAccessTokens.selectAll().where { RevokedAccessTokens.tokenId eq tokenId }.empty()
            }
        }
    }

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = (cur.message ?: "").lowercase()
            if (cur is java.sql.SQLException && cur.sqlState == "23505") return true
            if (msg.contains("unique") || msg.contains("duplicate key")) return true
            cur = cur.cause
        }
        return false
    }

    fun revokeAccessTokenFromAuthorizationHeader(authorizationHeader: String?): Boolean {
        val token = authorizationHeader
            ?.trim()
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return revokeAccessToken(token)
    }

    fun deleteExpired(now: Long = System.currentTimeMillis()): Int {
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
            AuthSessions.deleteWhere { AuthSessions.revokedAt less now - SESSION_ROW_RETENTION_MS }
            refreshDeleted
        }
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REFRESH_TOKEN_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000
        /** 8.34：每用户最大活跃会话数（超出淘汰最旧）。 */
        const val MAX_ACTIVE_SESSIONS = 10
        /** 8.34：已吊销会话行保留期（之后物理删除，防表无界增长）。 */
        const val SESSION_ROW_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        private val secureRandom = SecureRandom()
    }
}
