package com.maodouchat.server.repository

// access token 吊销簇：单 token 吊销与 Authorization 头吊销（原 AuthTokenAccess 扩展按域拆出，零行为改动）。

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.RevokedAccessTokens
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

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
