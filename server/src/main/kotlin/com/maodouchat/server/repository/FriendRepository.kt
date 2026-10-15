package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.FriendRequestResponse
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

class FriendRepository {

    internal fun blockedPeerIds(userId: String): Set<String> =
        BlockedUsers.selectAll()
            .where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .map { row ->
                val blocker = row[BlockedUsers.blockerId]
                val blocked = row[BlockedUsers.blockedId]
                if (blocker == userId) blocked else blocker
            }
            .toSet()

    // 8.48 修复 H1：批量映射——此前 mapRequest 逐行查 Users（limit 100 → 100 次查询）
    internal fun mapRequestList(rows: List<ResultRow>): List<FriendRequestResponse> {
        if (rows.isEmpty()) return emptyList()
        val userIds = rows.flatMap { listOf(it[FriendRequests.fromUserId], it[FriendRequests.toUserId]) }.distinct()
        val userMap = Users.selectAll()
            .where { (Users.id inList userIds) and Users.deletedAt.isNull() }
            .associateBy { it[Users.id] }
        return rows.mapNotNull { mapRequest(it, userMap) }
    }

    internal fun friendCountInTransaction(userId: String): Long =
        Friendships.selectAll().where {
            (Friendships.userLowId eq userId) or (Friendships.userHighId eq userId)
        }.count()

    internal fun areFriendsInTransaction(a: String, b: String): Boolean {
        if (a == b) return false
        val (low, high) = orderedPair(a, b)
        return Friendships.selectAll()
            .where { (Friendships.userLowId eq low) and (Friendships.userHighId eq high) }
            .firstOrNull() != null
    }

    internal fun ensureFriendship(a: String, b: String, now: Long) {
        val (low, high) = orderedPair(a, b)
        val exists = Friendships.selectAll()
            .where { (Friendships.userLowId eq low) and (Friendships.userHighId eq high) }
            .firstOrNull() != null
        if (!exists) {
            Friendships.insert {
                it[userLowId] = low
                it[userHighId] = high
                it[createdAt] = now
            }
        }
    }

    internal fun loadRequest(id: String): FriendRequestResponse? =
        FriendRequests.selectAll().where { FriendRequests.id eq id }.firstOrNull()?.let {
            // 写路径（accept/reject/cancel/send）必须能返回 DTO：对端刚注销时
            // 过滤 deletedAt 会让 mapRequest 返回 null，调用方再 !! 就是 500。
            mapRequest(it, emptyMap(), includeDeleted = true)
        }

    internal fun mapRequest(
        row: ResultRow,
        userMap: Map<String, ResultRow>,
        includeDeleted: Boolean = false
    ): FriendRequestResponse? {
        val fromId = row[FriendRequests.fromUserId]
        val toId = row[FriendRequests.toUserId]
        // 列表路径已由 mapRequestList 批量取回（不含注销用户）；单条路径（userMap 空）在此回查
        val userRows = if (userMap.isNotEmpty()) userMap else {
            val base = Users.selectAll().where { Users.id inList listOf(fromId, toId) }
            val query = if (includeDeleted) base else base.andWhere { Users.deletedAt.isNull() }
            query.associateBy { it[Users.id] }
        }
        val fromUser = userRows[fromId] ?: return null
        val toUser = userRows[toId] ?: return null
        fun publicUser(u: ResultRow) = UserResponse(
            id = u[Users.id],
            name = u[Users.name],
            email = "",
            avatar = u[Users.avatar],
            status = if (u[Users.showStatus]) u[Users.status] else "",
            isOnline = if (u[Users.showOnline]) u[Users.isOnline] else false,
            isModerator = false
        )
        return FriendRequestResponse(
            id = row[FriendRequests.id],
            fromUser = publicUser(fromUser),
            toUser = publicUser(toUser),
            message = row[FriendRequests.message],
            status = row[FriendRequests.status],
            createdAt = row[FriendRequests.createdAt],
            updatedAt = row[FriendRequests.updatedAt]
        )
    }

    internal fun isBlockedEitherWay(a: String, b: String): Boolean =
        BlockedUsers.selectAll()
            .where {
                ((BlockedUsers.blockerId eq a) and (BlockedUsers.blockedId eq b)) or
                    ((BlockedUsers.blockerId eq b) and (BlockedUsers.blockedId eq a))
            }
            .firstOrNull() != null

    internal fun orderedPair(a: String, b: String): Pair<String, String> =
        if (a <= b) a to b else b to a

    internal fun lockUserPair(a: String, b: String) =
        Users.selectAll()
            .where { Users.id inList listOf(a, b).distinct() }
            .orderBy(Users.id to SortOrder.ASC)
            .forUpdate()
            .toList()

    internal fun isValidUserId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    internal fun isValidRequestId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    companion object {
        internal const val MAX_MESSAGE_LEN = 300
        /** 单用户好友数上限（防超大好友集 fanout 资源耗尽）。 */
        const val MAX_FRIENDS_PER_USER = 2_000
    }
}
