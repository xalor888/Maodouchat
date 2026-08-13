package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.UserLocations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.NearbyLocationStatusResponse
import com.maodouchat.server.model.NearbyUserResponse
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class NearbyRepository {

    fun updateLocation(userId: String, latitude: Double, longitude: Double): NearbyLocationStatusResponse? = transaction {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return@transaction null
        }
        val user = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        if (user[Users.deletedAt] != null) return@transaction null
        val now = System.currentTimeMillis()
        val expiresAt = now + LOCATION_TTL_MS
        val coarseLatitude = round(latitude * 1_000.0) / 1_000.0
        val coarseLongitude = round(longitude * 1_000.0) / 1_000.0
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
            // 并发首次开启定位时两个请求都可能走 exists=false → 双 INSERT 撞 PK 500。
            // 捕获唯一冲突后转 UPDATE，保证幂等（B8 并发加固）。
            try {
                UserLocations.insert {
                    it[UserLocations.userId] = userId
                    it[UserLocations.latitude] = coarseLatitude
                    it[UserLocations.longitude] = coarseLongitude
                    it[UserLocations.visible] = true
                    it[UserLocations.updatedAt] = now
                    it[UserLocations.expiresAt] = expiresAt
                }
            } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                // 8.38：只对「唯一冲突」（并发首插撞 PK）转 UPDATE——死锁/连接中断/其他约束失败
                // 不得静默转 UPDATE 假装成功（用户以为已开启分享实际未落库）
                if (!isUniqueViolation(conflict)) throw conflict
                // 8.48 修复：PG/H2 唯一约束冲突后当前事务已 abort，任何语句都报错——
                // 必须在「新事务」转 UPDATE（此前同一事务内 UPDATE 是死代码，并发首次定位必 500）
                org.jetbrains.exposed.sql.transactions.transaction {
                    UserLocations.update({ UserLocations.userId eq userId }) {
                        it[UserLocations.latitude] = coarseLatitude
                        it[UserLocations.longitude] = coarseLongitude
                        it[UserLocations.visible] = true
                        it[UserLocations.updatedAt] = now
                        it[UserLocations.expiresAt] = expiresAt
                    }
                }
            }
        }
        NearbyLocationStatusResponse(true, expiresAt)
    }

    fun stopSharing(userId: String): Boolean = transaction {
        // > 0 ensures a real row was deleted. Idempotent success: after this call the
        // user is guaranteed to no longer share location, so callers treat both outcomes
        // (row existed or not) as "no longer sharing"; the boolean reflects an actual delete.
        UserLocations.deleteWhere { UserLocations.userId eq userId } > 0
    }

    fun getStatus(userId: String): NearbyLocationStatusResponse = transaction {
        val now = System.currentTimeMillis()
        val row = UserLocations.selectAll().where { UserLocations.userId eq userId }.firstOrNull()
        val sharing = row != null && row[UserLocations.visible] && row[UserLocations.expiresAt] > now
        NearbyLocationStatusResponse(sharing, if (sharing) row!![UserLocations.expiresAt] else 0)
    }

    fun getNearby(userId: String, radiusKm: Double = 10.0, limit: Int = 50): List<NearbyUserResponse> = transaction {
        val now = System.currentTimeMillis()
        // 限流清理：每 5 分钟最多执行一次过期位置清理，避免高并发下每次查询都触发 DELETE
        val lastCleanup = lastNearbyCleanupAt.get()
        if (now - lastCleanup >= NEARBY_CLEANUP_INTERVAL_MS && lastNearbyCleanupAt.compareAndSet(lastCleanup, now)) {
            UserLocations.deleteWhere { UserLocations.expiresAt lessEq now }
        }
        val self = UserLocations.selectAll().where { UserLocations.userId eq userId }.firstOrNull()
            ?: return@transaction emptyList()
        if (!self[UserLocations.visible] || self[UserLocations.expiresAt] <= now) return@transaction emptyList()

        val radius = radiusKm.coerceIn(0.5, 30.0)
        val originLat = self[UserLocations.latitude]
        val originLon = self[UserLocations.longitude]
        val latDelta = radius / 111.0
        val lonScale = cos(originLat * PI / 180.0).coerceAtLeast(0.1)
        val lonDelta = radius / (111.0 * lonScale)
        val minLongitude = originLon - lonDelta
        val maxLongitude = originLon + lonDelta
        val longitudeBounds = when {
            minLongitude < -180.0 ->
                (UserLocations.longitude greaterEq minLongitude + 360.0) or (UserLocations.longitude lessEq maxLongitude)
            maxLongitude > 180.0 ->
                (UserLocations.longitude greaterEq minLongitude) or (UserLocations.longitude lessEq maxLongitude - 360.0)
            else ->
                (UserLocations.longitude greaterEq minLongitude) and (UserLocations.longitude lessEq maxLongitude)
        }
        val blockedIds = BlockedUsers.selectAll().where {
            (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId)
        }.map { row ->
            if (row[BlockedUsers.blockerId] == userId) row[BlockedUsers.blockedId] else row[BlockedUsers.blockerId]
        }.toSet()

        val boundedLimit = limit.coerceIn(1, 100)
        val visible = mutableListOf<Pair<ResultRow, Double>>()
        var cursorUserId: String? = null
        var iterations = 0
        // 游标分批拉取：前几批若被拉黑/已注销/停权用户占满，继续拉下一批，
        // 不能因粗上限过滤后不足而少返回（与点赞者分页同一修复模式）。
        while (visible.size < boundedLimit && iterations < 20) {
            val batchSize = ((boundedLimit - visible.size) * 4).coerceAtLeast(boundedLimit)
            val cursorCondition = if (cursorUserId == null) {
                (UserLocations.userId neq userId)
            } else {
                (UserLocations.userId greater cursorUserId)
            }
            val batch = (UserLocations innerJoin Users).selectAll().where {
                (UserLocations.visible eq true) and
                    (UserLocations.expiresAt greaterEq now) and
                    cursorCondition and
                    (UserLocations.latitude greaterEq originLat - latDelta) and
                    (UserLocations.latitude lessEq originLat + latDelta) and
                    longitudeBounds
            }
                .orderBy(UserLocations.userId to SortOrder.ASC)
                .limit(batchSize)
                .toList()
            if (batch.isEmpty()) break
            batch.forEach { row ->
                // 8.33 修复：封禁用户必须从「附近的人」消失（位置 = 实时行踪，封禁期间不可见）
                if (row[Users.deletedAt] == null &&
                    row[Users.suspendedUntil] <= now &&
                    row[Users.id] !in blockedIds
                ) {
                    val distance = haversineMeters(
                        originLat,
                        originLon,
                        row[UserLocations.latitude],
                        row[UserLocations.longitude]
                    )
                    if (distance <= radius * 1_000.0) {
                        visible += row to distance
                    }
                }
            }
            cursorUserId = batch.last()[UserLocations.userId]
            iterations++
        }
        visible
            .sortedBy { (_, distance) -> distance }
            .take(boundedLimit)
            .map { (row, distance) ->
                NearbyUserResponse(
                    user = row.toPublicUser(),
                    distanceMeters = distance.toInt().coerceAtLeast(MIN_DISPLAY_DISTANCE_METERS),
                    locationUpdatedAt = row[UserLocations.updatedAt]
                )
            }
    }

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    private fun ResultRow.toPublicUser() = UserResponse(
        id = this[Users.id],
        name = this[Users.name],
        email = "",
        avatar = this[Users.avatar],
        status = if (this[Users.showStatus]) this[Users.status] else "",
        isOnline = this[Users.showOnline] && this[Users.isOnline]
    )

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val LOCATION_TTL_MS = 45L * 60L * 1000L
        const val MIN_DISPLAY_DISTANCE_METERS = 100
        const val NEARBY_CLEANUP_INTERVAL_MS = 5L * 60L * 1000L
        val lastNearbyCleanupAt = AtomicLong(0)
    }
}
