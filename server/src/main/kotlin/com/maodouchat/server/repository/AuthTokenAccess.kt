package com.maodouchat.server.repository

// access token 版本与吊销簇：原 AuthTokenRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RevokedAccessTokens
import com.maodouchat.server.db.Users
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun AuthTokenRepository.getAccessTokenVersion(userId: String): Long {
    return transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.accessTokenVersion)
            ?: 0
    }
}

fun AuthTokenRepository.rotateAccessTokenVersion(userId: String): Long {
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

fun AuthTokenRepository.isAccessTokenAllowed(
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

fun AuthTokenRepository.revokeAccessToken(accessToken: String): Boolean {
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

fun AuthTokenRepository.revokeAccessTokenFromAuthorizationHeader(authorizationHeader: String?): Boolean {
    val token = authorizationHeader
        ?.trim()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return false
    return revokeAccessToken(token)
}
