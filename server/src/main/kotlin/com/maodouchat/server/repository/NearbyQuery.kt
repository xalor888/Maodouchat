package com.maodouchat.server.repository

// 附近的人查询：范围检索 + 距离计算。

import com.maodouchat.server.db.UserLocations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.NearbyUserResponse
import com.maodouchat.server.model.UserResponse
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private const val MIN_DISPLAY_DISTANCE_METERS = 100
// 9.240：距离展示量化粒度（防多点三角定位）
private const val DISTANCE_QUANTUM_METERS = 100
private const val NEARBY_CLEANUP_INTERVAL_MS = 5L * 60L * 1000L
private val lastNearbyCleanupAt = AtomicLong(0)

fun NearbyRepository.getNearby(userId: String, radiusKm: Double = 10.0, limit: Int = 50): List<NearbyUserResponse> = transaction {
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
    val blockedIds = ConversationVisibility.blockedUserIdsInTx(userId)

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
                // 9.240：距离按 100m 量化——1m 精度下多点查询可三角定位对方实时位置，
                // 量化后与 MIN_DISPLAY_DISTANCE_METERS 的隐私下限同粒度，排序仍保序
                distanceMeters = ((distance.toInt() + 99) / DISTANCE_QUANTUM_METERS * DISTANCE_QUANTUM_METERS)
                    .coerceAtLeast(MIN_DISPLAY_DISTANCE_METERS),
                locationUpdatedAt = row[UserLocations.updatedAt]
            )
        }
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
