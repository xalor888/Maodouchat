package com.maodouchat.server.repository

// 位置分享生命周期：更新/停止/状态查询。

import com.maodouchat.server.db.UserLocations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.NearbyLocationStatusResponse
import com.maodouchat.server.service.isUniqueViolation
import kotlin.math.round
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private const val LOCATION_TTL_MS = 45L * 60L * 1000L

fun NearbyRepository.updateLocation(userId: String, latitude: Double, longitude: Double): NearbyLocationStatusResponse? {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        return null
    }
    val now = System.currentTimeMillis()
    val expiresAt = now + LOCATION_TTL_MS
    val coarseLatitude = round(latitude * 1_000.0) / 1_000.0
    val coarseLongitude = round(longitude * 1_000.0) / 1_000.0
    // 并发首次开启定位时两个请求都可能走 exists=false → 双 INSERT 撞 PK。
    // 8.38/8.48 的「同事务 catch 后转 UPDATE」在 PG 上仍会 500：唯一冲突使当前事务 abort，
    // 嵌套 transaction{}（useNestedTransactions=false）复用同一连接，同样无法执行任何语句。
    // 正确姿势：catch 必须在事务外——Exposed 已回滚并归还连接，在全新事务重试（B8 并发加固）。
    return try {
        transaction {
            val user = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
            if (user[Users.deletedAt] != null) return@transaction null
            val exists = UserLocations.selectAll().where { UserLocations.userId eq userId }.firstOrNull() != null
            if (exists) {
                UserLocations.update({ UserLocations.userId eq userId }) {
                    it[UserLocations.latitude] = coarseLatitude
                    it[UserLocations.longitude] = coarseLongitude
                    it[UserLocations.visible] = true
                    it[UserLocations.updatedAt] = now
                    it[UserLocations.expiresAt] = expiresAt
                }
            } else {
                UserLocations.insert {
                    it[UserLocations.userId] = userId
                    it[UserLocations.latitude] = coarseLatitude
                    it[UserLocations.longitude] = coarseLongitude
                    it[UserLocations.visible] = true
                    it[UserLocations.updatedAt] = now
                    it[UserLocations.expiresAt] = expiresAt
                }
            }
            NearbyLocationStatusResponse(true, expiresAt)
        }
    } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
        // 只对「唯一冲突」（并发首插撞 PK）转重试——死锁/连接中断/其他约束失败
        // 不得静默转 UPDATE 假装成功（用户以为已开启分享实际未落库）
        if (!isUniqueViolation(conflict)) throw conflict
        transaction {
            val exists = UserLocations.selectAll().where { UserLocations.userId eq userId }.firstOrNull() != null
            if (exists) {
                UserLocations.update({ UserLocations.userId eq userId }) {
                    it[UserLocations.latitude] = coarseLatitude
                    it[UserLocations.longitude] = coarseLongitude
                    it[UserLocations.visible] = true
                    it[UserLocations.updatedAt] = now
                    it[UserLocations.expiresAt] = expiresAt
                }
            } else {
                // 胜者事务异常回滚的极端窗口：重试也兜底成 INSERT
                UserLocations.insert {
                    it[UserLocations.userId] = userId
                    it[UserLocations.latitude] = coarseLatitude
                    it[UserLocations.longitude] = coarseLongitude
                    it[UserLocations.visible] = true
                    it[UserLocations.updatedAt] = now
                    it[UserLocations.expiresAt] = expiresAt
                }
            }
            NearbyLocationStatusResponse(true, expiresAt)
        }
    }
}

fun NearbyRepository.stopSharing(userId: String): Boolean = transaction {
    // > 0 ensures a real row was deleted. Idempotent success: after this call the
    // user is guaranteed to no longer share location, so callers treat both outcomes
    // (row existed or not) as "no longer sharing"; the boolean reflects an actual delete.
    UserLocations.deleteWhere { UserLocations.userId eq userId } > 0
}

fun NearbyRepository.getStatus(userId: String): NearbyLocationStatusResponse = transaction {
    val now = System.currentTimeMillis()
    val row = UserLocations.selectAll().where { UserLocations.userId eq userId }.firstOrNull()
    val expiresAt = row
        ?.takeIf { it[UserLocations.visible] && it[UserLocations.expiresAt] > now }
        ?.get(UserLocations.expiresAt) ?: 0
    NearbyLocationStatusResponse(expiresAt > 0, expiresAt)
}
