package com.maodouchat.server.repository

// access token 版本簇：版本查询与轮换（原 AuthTokenAccess 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
